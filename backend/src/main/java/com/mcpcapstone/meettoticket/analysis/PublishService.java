package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.user.UserRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;


import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

// "확인한 N건 발행" (REQ-016)과 발행 결과 저장 (REQ-018). 발행은 Agent가 하고, 백엔드는 확인된 티켓만 보내고 결과를 저장한다.
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublishService {
    private final TaskItemRepository items;
    private final PublishBatchRepository batches;
    private final UserRepository users;
    private final PublishGateway gateway;
    private final JsonCodec json;
    private final TransactionTemplate tx;
    private final PublicationTicketMapper publicationMapper;

    // 확인한 티켓을 발행 중으로 바꿔 한 묶음으로 보낸다. 중복 클릭은 이미 발행 중이어서 담기지 않는다.
    // Agent 호출은 트랜잭션 밖에서 해 DB 연결을 붙잡지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Started start(Long userId, Long projectId) {
        return dispatch(tx.execute(status -> {
            List<TaskItem> picked = new ArrayList<>();
            for (UUID id : items.findIdsAssignedTo(userId, TicketStatus.CONFIRMED, projectId)) {
                // 잠근 뒤 다시 확인해야 그 사이 발행 중으로 바뀐 티켓을 걸러낸다.
                items.findForUpdate(id)
                        .filter(i -> i.getTicketStatus() == TicketStatus.CONFIRMED && publishable(i.getPublishTarget()))
                        .ifPresent(picked::add);
            }
            if (picked.isEmpty()) throw new BusinessException(ErrorCode.NO_CONFIRMED_TICKETS);
            return prepare(userId, picked);
        }));
    }

    // 회의 + 로그인 담당자 단위 발행. 잠근 뒤 담당자와 상태를 다시 검증한다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Started startMeeting(Long userId, Long projectId, Long meetingId, List<UUID> expectedTicketIds) {
        return dispatch(tx.execute(status -> {
            List<TaskItem> group = new ArrayList<>();
            for (UUID id : items.findIdsAssignedToMeeting(userId, meetingId)) {
                items.findForUpdate(id).filter(i -> i.getAssignee() != null
                        && i.getAssignee().getUser() != null
                        && i.getAssignee().getUser().getId().equals(userId)
                        && i.getTask().getMeeting().getId().equals(meetingId)
                        && (projectId == null || i.getTask().getMeeting().getProject().getId().equals(projectId)))
                        .ifPresent(group::add);
            }
            if (group.stream().anyMatch(i -> i.getTicketStatus() == TicketStatus.PENDING_REVIEW
                    || i.getTicketStatus() == TicketStatus.ASSIGNEE_REQUIRED))
                throw new BusinessException(ErrorCode.MEETING_REVIEW_INCOMPLETE);
            if (group.stream().anyMatch(i -> i.getTicketStatus() == TicketStatus.PUBLISHING))
                throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);
            List<TaskItem> picked = group.stream().filter(i -> i.getTicketStatus() == TicketStatus.CONFIRMED).toList();
            if (picked.isEmpty()) throw new BusinessException(ErrorCode.NO_CONFIRMED_TICKETS);
            if (picked.stream().anyMatch(i -> !publishable(i.getPublishTarget())))
                throw new BusinessException(ErrorCode.PUBLISH_TARGET_UNSUPPORTED);
            if (expectedTicketIds == null || !new HashSet<>(expectedTicketIds).equals(
                    new HashSet<>(picked.stream().map(TaskItem::getId).toList())))
                throw new BusinessException(ErrorCode.PUBLISH_SELECTION_CHANGED);
            return prepare(userId, picked);
        }));
    }

    // 실패한 티켓 하나를 다시 발행한다 (REQ-019). 담당자만 할 수 있고 실패 상태가 아니면 409다.
    // 티켓 행을 잠근 뒤 발행 중으로 바꾸므로 같은 티켓을 동시에 재시도해도 한쪽만 담기고 나머지는 409다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Started retry(UUID ticketId, Long userId) {
        return dispatch(tx.execute(status -> {
            TaskItem ticket = items.findForUpdate(ticketId).orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
            var assignee = ticket.getAssignee();
            if (assignee == null || assignee.getUser() == null || !assignee.getUser().getId().equals(userId))
                throw new BusinessException(ErrorCode.TICKET_ASSIGNEE_REQUIRED);
            if (ticket.getTicketStatus() != TicketStatus.FAILED) throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);
            // 재대조할 수 없는 대상의 결과 모름은 Agent가 어차피 막으므로, 확인 결과(resolve-outcome)를 먼저 남겨야 한다 (#280).
            if (PublishResolution.of(ticket) == PublishResolution.MANUAL_CHECK) throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);
            return prepare(userId, List.of(ticket));
        }));
    }

    // GitHub 이슈, Notion 페이지, Slack 공지(TASK-051) 모두 Agent 일괄 발행이 처리한다.
    private static boolean publishable(PublishTarget target) {
        return target == PublishTarget.GITHUB || target == PublishTarget.NOTION || target == PublishTarget.SLACK;
    }

    private record Prepared(UUID batchId, PublishGateway.Request request) {}

    // 묶음을 만들고 티켓을 발행 중으로 바꾼다. 트랜잭션 안에서 부른다.
    private Prepared prepare(Long userId, List<TaskItem> picked) {
        PublishBatch batch = batches.save(new PublishBatch(users.getReferenceById(userId), gateway.mode()));
        List<PublishGateway.Ticket> tickets = new ArrayList<>();
        for (TaskItem i : picked) {
            tickets.add(toTicket(i));
            i.startPublishing(batch);
        }
        return new Prepared(batch.getId(), new PublishGateway.Request(batch.getId().toString(), tickets));
    }

    private Started dispatch(Prepared prepared) {
        Optional<PublishGateway.Result> immediate;
        try {
            immediate = gateway.submit(prepared.request());
        } catch (AgentException e) {
            if (!e.uncertain()) {
                // Agent가 받지 않은 것이 확실하면 티켓을 되돌려 다시 누를 수 있게 한다.
                abort(prepared.batchId(), e.getMessage());
                throw new BusinessException(ErrorCode.AGENT_ACCEPTANCE_FAILED);
            }
            // 받았는지 모르면 발행 중으로 두고 Agent 보고를 기다린다.
            log.warn("일괄 발행 {} 접수 확인 실패, 결과를 기다림", prepared.batchId());
            immediate = Optional.empty();
        }
        // 같은 클래스 호출은 프록시를 거치지 않으므로 트랜잭션을 직접 연다.
        immediate.ifPresent(result -> tx.executeWithoutResult(status -> applyResult(prepared.batchId(), result)));
        return new Started(prepared.batchId(), prepared.request().tickets().size());
    }

    private void abort(UUID batchId, String message) {
        tx.executeWithoutResult(status -> {
            items.findByBatch(batchId).forEach(TaskItem::cancelPublishing);
            batches.findForUpdate(batchId).ifPresent(b -> b.finish(PublishBatchStatus.FAILED, message));
        });
    }

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void reconcileRunning() {
		for (UUID id : batches.findIdsByStatusCreatedBefore(PublishBatchStatus.RUNNING, OffsetDateTime.now())) {
			try {
				gateway.status(id.toString()).ifPresent(result ->
						tx.executeWithoutResult(status -> applyResult(id, result)));
			} catch (RuntimeException e) {
				log.warn("일괄 발행 {} 결과 조회 실패, 다음 주기에 다시 조회: {}", id, e.getMessage());
			}
		}
	}

    // 접수 확인에 실패했거나 Agent가 보고하지 못한 묶음은 기한이 지나면 결과 모름으로 끝낸다.
    // 그대로 두면 티켓이 발행 중에 묶여 다시 발행할 수 없다. 기한 뒤에 온 보고는 이미 끝난 묶음이라 무시된다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int expireStale(Duration timeout) {
        List<UUID> stale = batches.findIdsByStatusCreatedBefore(PublishBatchStatus.RUNNING, OffsetDateTime.now().minus(timeout));
        PublishGateway.Result timedOut = new PublishGateway.Result(
                null, "FAILED", null, List.of(), "RESULT_TIMEOUT",
                "기한 안에 발행 결과를 받지 못했습니다. GitHub에서 발행 여부를 확인한 뒤 다시 시도하세요.");
        for (UUID id : stale) {
            log.warn("일괄 발행 {} 결과를 {} 안에 받지 못해 결과 모름으로 끝냄", id, timeout);
            tx.executeWithoutResult(status -> applyResult(id, timedOut));
        }
        return stale.size();
    }

    // Agent 보고를 티켓 상태로 옮긴다. 이미 끝난 묶음의 보고는 무시해 중복 보고에도 한 번만 반영한다.
    @Transactional
    public void applyResult(UUID batchId, PublishGateway.Result result) {
        PublishBatch batch = batches.findForUpdate(batchId).orElseThrow(() -> new BusinessException(ErrorCode.PUBLISH_BATCH_NOT_FOUND));
        if (batch.getStatus().terminal()) return;

        Map<String, TaskItem> waiting = new HashMap<>();
        for (TaskItem i : items.findByBatch(batchId))
            if (i.getTicketStatus() == TicketStatus.PUBLISHING) waiting.put(i.getId().toString(), i);

        for (PublishGateway.Item r : result.items() == null ? List.<PublishGateway.Item>of() : result.items()) {
            TaskItem ticket = waiting.remove(r.ticketId());
            if (ticket == null) continue; // 이 묶음의 발행 중 티켓이 아니면 반영하지 않는다
            record(ticket, r);
        }
        // 보고에 없는 티켓은 발행됐는지 알 수 없다. 실패로 두되 결과 모름으로 구분한다.
        for (TaskItem ticket : waiting.values())
            ticket.recordPublishResult(
                    TicketStatus.FAILED, null, null, null,
                    result.errorCode() != null ? result.errorCode() : "RESULT_MISSING",
                    result.errorMessage() != null ? result.errorMessage() : "발행 결과를 받지 못했습니다.",
                    false, true);

        List<TaskItem> all = items.findByBatch(batchId);
        long failed = all.stream().filter(i -> i.getTicketStatus() == TicketStatus.FAILED).count();
        // 발행 중에 프로젝트가 삭제되면 티켓이 남지 않는다. 성공으로 보이지 않게 실패로 끝낸다.
        if (all.isEmpty()) {
            batch.finish(PublishBatchStatus.FAILED, "묶음의 티켓이 남아 있지 않습니다.");
            return;
        }
        batch.finish(
                failed == 0 ? PublishBatchStatus.SUCCEEDED : failed == all.size() ? PublishBatchStatus.FAILED : PublishBatchStatus.PARTIAL_FAILED,
                result.errorMessage(), json.write(all.stream().map(PublishService::entry).toList()));
    }

    private void record(TaskItem ticket, PublishGateway.Item r) {
        ProcessingType executed = parse(r.processingType());
        // 저장 칸 이름은 githubIssueUrl이지만 Notion 대상은 만든 페이지 URL을, Slack 대상은 게시한 메시지 URL을 담는다(스키마는 그대로).
        String url = r.notionPageUrl() != null ? r.notionPageUrl() : r.messageUrl() != null ? r.messageUrl() : r.githubIssueUrl();
        switch (r.status() == null ? "" : r.status()) {
            // Slack 공지는 Agent가 POSTED로 알려 준다. 티켓 상태로는 발행됨이다.
            case "PUBLISHED", "POSTED" -> ticket.recordPublishResult(TicketStatus.PUBLISHED, executed, r.githubIssueNumber(), url, r.errorCode(), r.errorMessage(), false, false);
            case "LINKED" -> ticket.recordPublishResult(TicketStatus.LINKED, executed, r.githubIssueNumber(), url, r.errorCode(), r.errorMessage(), false, false);
            // 본문은 갱신됐지만 알림 댓글만 실패한 경우처럼 errorCode가 함께 올 수 있다. 갱신됨으로 두고 사유를 남긴다.
            case "UPDATED" -> ticket.recordPublishResult(TicketStatus.UPDATED, executed, r.githubIssueNumber(), url, r.errorCode(), r.errorMessage(), retryable(r), false);
			case "SKIPPED" -> ticket.recordPublishResult(TicketStatus.SKIPPED, executed, null, null,
					null, r.errorMessage() != null ? r.errorMessage() : "발행하지 않음", false, false);
            case "UNKNOWN" -> ticket.recordPublishResult(TicketStatus.FAILED, executed, r.githubIssueNumber(), url,
                    r.errorCode() != null ? r.errorCode() : "RESULT_UNKNOWN", r.errorMessage(), retryable(r), true);
            default -> ticket.recordPublishResult(TicketStatus.FAILED, executed, null, null,
                    r.errorCode() != null ? r.errorCode() : "PUBLISH_FAILED", r.errorMessage(), retryable(r), false);
        }
    }

    private static boolean retryable(PublishGateway.Item r) {
        return Boolean.TRUE.equals(r.retryable());
    }

    private static ProcessingType parse(String value) {
        try {
            return value == null ? null : ProcessingType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Result result(UUID batchId, Long userId) {
        PublishBatch batch = batches.findById(batchId).orElseThrow(() -> new BusinessException(ErrorCode.PUBLISH_BATCH_NOT_FOUND));
        // 다른 사용자의 묶음은 있는지도 알려주지 않는다.
        if (!batch.getUser().getId().equals(userId)) throw new BusinessException(ErrorCode.PUBLISH_BATCH_NOT_FOUND);
        // 끝난 묶음은 그때 저장한 기록을 보여 준다. 티켓이 그 뒤 다시 시도되거나 수정으로 되돌려져도 지난 결과가 바뀌지 않는다.
        List<Entry> entries = batch.getEntriesJson() != null
                ? List.of(json.convert(json.read(batch.getEntriesJson()), Entry[].class))
                : items.findByBatch(batchId).stream().map(PublishService::entry).toList();
        return new Result(batch.getId(), batch.getStatus(), batch.getExecutionMode(), batch.getCreatedAt(), batch.getFinishedAt(), summary(entries), entries);
    }

    private static Summary summary(List<Entry> list) {
        return new Summary(
                list.size(),
                count(list, TicketStatus.PUBLISHED),
                count(list, TicketStatus.LINKED),
                count(list, TicketStatus.UPDATED),
				count(list, TicketStatus.SKIPPED),
                count(list, TicketStatus.FAILED),
                count(list, TicketStatus.PUBLISHING));
    }

    private static int count(List<Entry> list, TicketStatus status) {
        return (int) list.stream().filter(e -> e.status() == status).count();
    }

    private static Entry entry(TaskItem i) {
        var project = i.getTask().getMeeting().getProject();
        // 발행 중인 티켓은 이전 실패 사유를 보여 주지 않는다 (재시도 중)
        boolean waiting = i.getTicketStatus() == TicketStatus.PUBLISHING;
        String code = waiting ? null : i.getPublishErrorCode();
        return new Entry(
                i.getId(), i.getTitle(), i.getTicketStatus(), i.getProcessingType(), project.getId(), project.getName(),
                i.getGithubIssueNumber(), i.getGithubIssueUrl(), code, waiting ? null : i.getErrorMessage(),
                PublishFailureCategory.of(code),
                // 결과 모름은 카드와 같은 기준(지난 결과 모름 포함)으로 내려준다. Notion/Slack 차단 실패도 결과 모름으로 보인다.
                !waiting && i.isPublishRetryable(), !waiting && i.getTicketStatus() == TicketStatus.FAILED && i.mustRecheckOnPublish(), i.getPublishTarget());
    }

    // 담당자가 카드에서 고친 내용(contentJson)과 처리 유형을 그대로 보낸다. 발행 본문은 Agent가 이 내용으로 조립한다.
    private PublishGateway.Ticket toTicket(TaskItem i) {
        return publicationMapper.toTicket(i);
    }

    public record Started(
            @Schema(description = "발행 묶음 ID. 결과 조회에 쓴다", requiredMode = Schema.RequiredMode.REQUIRED, example = "5f1d6c1e-0f8b-4f6a-8f43-0b0a6d2b9e11") UUID batchId,
            @Schema(description = "발행에 담긴 확인된 티켓 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "3") int count) {}

    public record Result(
            @Schema(description = "발행 묶음 ID", requiredMode = Schema.RequiredMode.REQUIRED) UUID batchId,
            @Schema(description = "묶음 상태. RUNNING이면 결과를 기다리는 중", requiredMode = Schema.RequiredMode.REQUIRED, example = "PARTIAL_FAILED") PublishBatchStatus status,
            @Schema(description = "백엔드 실행 모드. MOCK는 실제 GitHub 발행을 하지 않는다", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MOCK", "LIVE"}, example = "LIVE") String executionMode,
            @Schema(description = "발행 요청 시각", requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt,
            @Schema(description = "결과를 받은 시각. 진행 중이면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) OffsetDateTime finishedAt,
            @Schema(description = "티켓 상태별 건수", requiredMode = Schema.RequiredMode.REQUIRED) Summary summary,
            @Schema(description = "티켓별 결과", requiredMode = Schema.RequiredMode.REQUIRED) List<Entry> tickets) {}

    public record Summary(
            @Schema(description = "묶음의 티켓 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "3") int total,
            @Schema(description = "발행됨", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int published,
            @Schema(description = "기존 이슈에 연결됨", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int linked,
            @Schema(description = "기존 이슈 갱신됨", requiredMode = Schema.RequiredMode.REQUIRED, example = "0") int updated,
			@Schema(description = "외부 발행 생략", requiredMode = Schema.RequiredMode.REQUIRED, example = "0") int skipped,
            @Schema(description = "실패", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int failed,
            @Schema(description = "결과를 기다리는 중", requiredMode = Schema.RequiredMode.REQUIRED, example = "0") int publishing) {}

    public record Entry(
            @Schema(description = "티켓 ID", requiredMode = Schema.RequiredMode.REQUIRED) UUID ticketId,
            @Schema(description = "티켓 제목", requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(description = "티켓 상태", requiredMode = Schema.RequiredMode.REQUIRED, example = "PUBLISHED") TicketStatus status,
            @Schema(description = "실제 실행한 처리 유형. 발행 직전 재대조로 신규 발행이 연결로 바뀔 수 있다", requiredMode = Schema.RequiredMode.REQUIRED, example = "CREATE") ProcessingType processingType,
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED) Long projectId,
            @Schema(description = "프로젝트 이름", requiredMode = Schema.RequiredMode.REQUIRED) String projectName,
            @Schema(description = "이슈 번호. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Integer githubIssueNumber,
            @Schema(description = "이슈 URL. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String githubIssueUrl,
            @Schema(description = "실패 사유 코드 (예: GITHUB_FORBIDDEN)", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String errorCode,
            @Schema(description = "실패 사유", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String errorMessage,
            @Schema(description = "실패 사유 분류. REPOSITORY_PERMISSION이면 PM이 프로젝트 설정에서 저장소 권한을 풀어야 하므로 화면이 프로젝트 설정으로 안내한다. 사유가 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, allowableValues = {"REPOSITORY_PERMISSION", "OTHER"}, example = "REPOSITORY_PERMISSION") PublishFailureCategory failureCategory,
            @Schema(description = "다시 시도해도 되는 실패인지", requiredMode = Schema.RequiredMode.REQUIRED) boolean retryable,
            @Schema(description = "요청은 갔지만 결과를 모르는 경우. 다시 시도하기 전에 발행 대상 도구에서 확인해야 한다", requiredMode = Schema.RequiredMode.REQUIRED) boolean outcomeUnknown,
            @Schema(description = "발행 대상. githubIssueUrl에는 대상에 따라 GitHub 이슈, Notion 페이지, Slack 메시지 URL이 담긴다. 이 필드가 생기기 전에 끝난 묶음은 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "GITHUB") PublishTarget publishTarget) {}
}
