package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.*;

// 발행 요청 DB 저장 → Agent 접수 → Agent 결과 콜백 저장을 분리한다. HTTP 대기 중 DB 트랜잭션을 붙잡지 않는다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicationBatchService {
    private final TaskItemRepository tickets;
    private final PublicationBatchRepository batches;
    private final PublicationBatchItemRepository batchItems;
    private final ProjectMemberRepository members;
    private final UserRepository users;
    private final PublishGateway agent;
    private final JsonCodec json;
    private final TransactionTemplate tx;

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Accepted start(Long userId) {
        Prepared prepared = tx.execute(status -> prepare(userId));
        try {
            agent.submit(prepared.request());
            return new Accepted(prepared.batchId(), PublicationBatchStatus.RUNNING, prepared.ticketCount());
        } catch (AgentException e) {
            if (e.uncertain()) {
                tx.executeWithoutResult(status -> markAcceptanceUncertain(prepared.batchId(), e.getMessage()));
                // Agent가 이미 발행을 시작했을 수 있으므로 티켓을 CONFIRMED로 되돌려 재요청하지 않는다.
                return new Accepted(prepared.batchId(), PublicationBatchStatus.ACCEPTANCE_UNCERTAIN, prepared.ticketCount());
            }
            tx.executeWithoutResult(status -> rejectBeforeAgent(prepared.batchId(), e.getMessage()));
            throw new BusinessException(ErrorCode.AGENT_ACCEPTANCE_FAILED);
        }
    }

    @Transactional
    public void applyResult(UUID batchId, ResultRequest request) {
        if (request.batchId() != null && !batchId.equals(request.batchId()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        PublicationBatch batch = batches.findForUpdate(batchId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PUBLICATION_BATCH_NOT_FOUND));
        // Agent의 재전송은 이미 저장된 최종 결과를 바꾸지 않는다.
        if (batch.getStatus().terminal()) return;

        // Agent의 묶음 상태는 형식만 검증한다. 최종 DB 상태는 항목 결과에서 다시 계산해
        // "SUCCEEDED인데 항목이 빠진" 잘못된 콜백이 성공으로 보이지 않게 한다.
        batchStatus(request.status());
        Map<UUID, ItemResultRequest> returned = returnedItems(request.items());
        List<PublicationBatchItem> expected = batchItems.findByBatchId(batchId);
        Set<UUID> expectedIds = new HashSet<>();
        for (PublicationBatchItem entry : expected) expectedIds.add(entry.getTicket().getId());
        if (!expectedIds.containsAll(returned.keySet())) throw new BusinessException(ErrorCode.VALIDATION_FAILED);

        List<String> itemStatuses = new ArrayList<>();
        for (PublicationBatchItem entry : expected) {
            ItemResultRequest result = returned.get(entry.getTicket().getId());
            if (result == null) {
                result = ItemResultRequest.missing(entry.getTicket().getId());
            }
            validateItemStatus(result.status(), result.processingType());
            PublicationBatchItem.PublicationResult stored = result.toResult();
            itemStatuses.add(stored.status());
            entry.record(stored, json.write(stored.droppedLabels()));
            entry.getTicket().recordPublication(
                    stored.status(), stored.githubIssueNumber(), stored.githubIssueUrl(),
                    stored.notionPageId(), stored.notionPageUrl(), stored.errorMessage());
        }
        batch.complete(derivedBatchStatus(itemStatuses), blankToNull(request.errorCode()), blankToNull(request.errorMessage()));
    }

    private Prepared prepare(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        List<TaskItem> confirmed = tickets.lockByAssigneeUserIdAndTicketStatus(userId, TicketStatus.CONFIRMED);
        if (confirmed.isEmpty()) throw new BusinessException(ErrorCode.NO_CONFIRMED_TICKETS);

        PublicationBatch batch = batches.save(new PublicationBatch(UUID.randomUUID(), user));
        List<AgentContracts.PublishTicket> requestTickets = new ArrayList<>();
        Map<Long, List<AgentContracts.SlackMember>> membersByProject = new HashMap<>();
        for (TaskItem ticket : confirmed) {
            ticket.beginPublication();
            batchItems.save(new PublicationBatchItem(batch, ticket));
            Project project = ticket.getTask().getMeeting().getProject();
            List<AgentContracts.SlackMember> slackMembers = membersByProject.computeIfAbsent(project.getId(), ignored ->
                    members.findByProjectIdOrderById(project.getId()).stream()
                            .filter(member -> member.getUser() != null && member.getUser().getSlackUserId() != null)
                            .map(member -> new AgentContracts.SlackMember(member.getName(), member.getUser().getSlackUserId()))
                            .toList());
            requestTickets.add(toAgentTicket(ticket, project, slackMembers));
        }
        return new Prepared(batch.getId(), confirmed.size(), new AgentContracts.PublishBatchRequest(batch.getId(), requestTickets));
    }

    private AgentContracts.PublishTicket toAgentTicket(
            TaskItem ticket, Project project, List<AgentContracts.SlackMember> slackMembers) {
        JsonNode content = json.read(ticket.getContentJson());
        return new AgentContracts.PublishTicket(
                ticket.getId(),
                new AgentContracts.PublishProject(
                        project.getId(), project.getName(), project.getGithubOwner(), project.getGithubRepo(),
                        project.getNotionPageUrl(), project.getSlackChannelId()),
                ticket.getTask().getMeeting().getId(),
                ticket.getTask().getMeeting().getTitle(),
                ticket.getTask().getMeeting().getMeetingDate(),
                ticket.getTitle(),
                content,
                ticket.getProcessingType().name(),
                targetIssueNumber(content),
                content.path("dedupe").path("handling").path("edited").asBoolean(false),
                ticket.getPublishTarget().name(),
                slackMembers,
                "CONFIRMED");
    }

    private static Integer targetIssueNumber(JsonNode content) {
        JsonNode handlingNumber = content.path("dedupe").path("handling").path("issue_number");
        if (handlingNumber.isIntegralNumber()) return handlingNumber.intValue();
        JsonNode judgedNumber = content.path("dedupe").path("issue_number");
        return judgedNumber.isIntegralNumber() ? judgedNumber.intValue() : null;
    }

    private void markAcceptanceUncertain(UUID batchId, String message) {
        batches.findForUpdate(batchId).ifPresent(batch -> batch.markAcceptanceUncertain(message));
    }

    private void rejectBeforeAgent(UUID batchId, String message) {
        PublicationBatch batch = batches.findForUpdate(batchId).orElseThrow();
        if (batch.getStatus().terminal()) return;
        for (PublicationBatchItem item : batchItems.findByBatchId(batchId))
            item.getTicket().restoreConfirmedAfterRejectedPublishRequest();
        batch.rejectBeforeAgent(message);
    }

    private static PublicationBatchStatus batchStatus(String status) {
        try {
            PublicationBatchStatus parsed = PublicationBatchStatus.valueOf(status);
            if (!parsed.terminal()) throw new IllegalArgumentException();
            return parsed;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private static PublicationBatchStatus derivedBatchStatus(List<String> itemStatuses) {
        boolean succeeded = itemStatuses.stream()
                .anyMatch(status -> Set.of("PUBLISHED", "LINKED", "UPDATED", "POSTED").contains(status));
        boolean failed = itemStatuses.stream().anyMatch(status -> status.equals("FAILED") || status.equals("UNKNOWN"));
        if (!failed) return PublicationBatchStatus.SUCCEEDED;
        return succeeded ? PublicationBatchStatus.PARTIAL_FAILED : PublicationBatchStatus.FAILED;
    }

    private static Map<UUID, ItemResultRequest> returnedItems(List<ItemResultRequest> items) {
        if (items == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        Map<UUID, ItemResultRequest> byId = new HashMap<>();
        for (ItemResultRequest item : items) {
            if (item == null || item.ticketId() == null || byId.put(item.ticketId(), item) != null)
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return byId;
    }

    private static void validateItemStatus(String status, String processingType) {
        if (!Set.of("PUBLISHED", "LINKED", "UPDATED", "POSTED", "FAILED", "UNKNOWN").contains(status)
                || !Set.of("CREATE", "LINK", "UPDATE").contains(processingType))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Prepared(UUID batchId, int ticketCount, AgentContracts.PublishBatchRequest request) {}

    public record Accepted(UUID batchId, PublicationBatchStatus status, int ticketCount) {}

    // Agent reporter의 camelCase JSON 계약. batchId는 경로와 일치해야 한다.
    public record ResultRequest(
            UUID batchId,
            String status,
            Map<String, Integer> summary,
            List<ItemResultRequest> items,
            String errorCode,
            String errorMessage) {}

    public record ItemResultRequest(
            UUID ticketId,
            String processingType,
            String status,
            Integer githubIssueNumber,
            String githubIssueUrl,
            String commentUrl,
            String messageUrl,
            String notionPageId,
            String notionPageUrl,
            List<String> droppedLabels,
            String errorCode,
            String errorMessage,
            boolean retryable) {
        static ItemResultRequest missing(UUID ticketId) {
            return new ItemResultRequest(ticketId, "CREATE", "UNKNOWN", null, null, null, null, null, null, List.of(),
                    "RESULT_MISSING", "Agent 결과에 이 티켓의 발행 결과가 없습니다.", true);
        }

        PublicationBatchItem.PublicationResult toResult() {
            return new PublicationBatchItem.PublicationResult(status, processingType, githubIssueNumber,
                    githubIssueUrl, commentUrl, messageUrl, notionPageId, notionPageUrl,
                    droppedLabels == null ? List.of() : droppedLabels,
                    errorCode, errorMessage, retryable);
        }
    }
}
