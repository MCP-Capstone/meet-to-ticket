package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.project.ProjectService;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 회의록 처리가 끝난 뒤 업로더가 보는 전송 결과 (REQ-010, REQ-011, REQ-012, TASK-023·024·025).
// 티켓별 받은 사람과 상태, GitHub 이슈 미리보기, 담당 지정 필요 티켓의 대표 담당 지정.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DispatchService {
    private final AnalysisTaskRepository tasks;
    private final TaskItemRepository items;
    private final ProjectMemberRepository members;
    private final ProjectService access;
    private final MyTicketService view;
    private final JsonCodec json;
    private final TicketHistoryRecorder recorder;

    // 같은 프로젝트 멤버는 누구나 볼 수 있다. 대표 담당 지정은 업로더만 한다 (canAssign).
    public DispatchResult result(UUID taskId, Long userId) {
        AnalysisTask task = tasks.findById(taskId).orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        Long projectId = task.getMeeting().getProject().getId();
        access.requireAccess(projectId, userId);
        if (!task.getStatus().terminal()) throw new BusinessException(ErrorCode.TASK_NOT_FINISHED);
        List<DispatchTicket> tickets = items.findByTaskIdOrderById(taskId).stream().map(i -> ticket(i, userId)).toList();
        boolean uploader = isUploader(task, userId);
        return new DispatchResult(
                taskId,
                counts(tickets),
                tickets,
                uploader,
                uploader ? members.findByProjectIdOrderById(projectId).stream()
                        .map(m -> new MemberOption(m.getId(), m.getName())).toList() : List.of());
    }

    // 담당자가 여러 명이거나 정해지지 않은 티켓에 대표 담당 1명을 지정하면 검토 대기가 되어 그 멤버의 내 티켓으로 간다 (REQ-011).
    @Transactional
    public DispatchTicket assign(UUID ticketId, Long userId, Long memberId) {
        TaskItem item = items.findForUpdate(ticketId).orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
        if (!isUploader(item.getTask(), userId)) throw new BusinessException(ErrorCode.TICKET_UPLOADER_REQUIRED);
        if (item.getTicketStatus() != TicketStatus.ASSIGNEE_REQUIRED) throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);
        Long projectId = item.getTask().getMeeting().getProject().getId();
        ProjectMember member = members.findById(memberId)
                .filter(m -> m.getProject().getId().equals(projectId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_MEMBER_NOT_FOUND));
        ObjectNode content = (ObjectNode) json.read(item.getContentJson());
        String login = member.getGithubName();
        List<String> github = login == null || login.isBlank() ? List.of() : List.of(login.trim());
        content.set("assignees", json.tree(Map.of(
                "names", List.of(member.getName()), "github", github,
                "unresolved", github.isEmpty() ? List.of(member.getName()) : List.of())));
        item.edit(member, json.write(content));
        item.assignTo(member);
        recorder.statusChanged(item, TicketStatus.ASSIGNEE_REQUIRED, userId, TicketHistoryRecorder.REASON_ASSIGN);
        return ticket(item, userId);
    }

    private static boolean isUploader(AnalysisTask task, Long userId) {
        var uploader = task.getMeeting().getCreatedBy();
        return uploader != null && uploader.getId().equals(userId);
    }

    private DispatchTicket ticket(TaskItem i, Long userId) {
        var assignee = i.getAssignee();
        boolean mine = assignee != null && assignee.getUser() != null && assignee.getUser().getId().equals(userId);
        return new DispatchTicket(view.view(i), mine, i.getPublishTarget() == PublishTarget.GITHUB ? preview(i) : null);
    }

    // Agent 본문의 나머지 섹션은 유지하고, 카드에서 바뀔 수 있는 세 섹션만 현재 저장값으로 갱신한다.
    private static final Pattern EDITABLE_SECTION = Pattern.compile(
            "(?ms)^## (작업 내용|담당|기한)\\r?\\n.*?(?=^## |^<details>|^---[ \\t]*$|\\z)");

    private IssuePreview preview(TaskItem i) {
        JsonNode c = json.read(i.getContentJson());
        String body = text(c.path("issue_body"));
        if (body == null) body = Objects.requireNonNullElse(text(c.path("description")), text(c.path("background")));
        else {
            Matcher sections = EDITABLE_SECTION.matcher(body);
            int background = body.indexOf("<details>");
            if (background >= 0) sections.region(0, background);
            StringBuilder updated = new StringBuilder();
            while (sections.find()) {
                String value = switch (sections.group(1)) {
                    case "작업 내용" -> Objects.requireNonNullElse(text(c.path("description")),
                            Objects.requireNonNullElse(text(c.path("action_text")), ""));
                    case "담당" -> assigneeBody(c.path("assignees"));
                    case "기한" -> dueBody(c.path("due"));
                    default -> throw new IllegalStateException("Unknown preview section");
                };
                sections.appendReplacement(updated, Matcher.quoteReplacement("## " + sections.group(1) + "\n" + value + "\n\n"));
            }
            sections.appendTail(updated);
            body = updated.toString();
        }
        return new IssuePreview(body, strings(c.path("labels")), strings(c.path("evidence_quotes")));
    }

    private static String assigneeBody(JsonNode assignees) {
        List<String> names = strings(assignees.path("names"));
        String body = "- 회의록 담당자: " + (names.isEmpty() ? "없음" : String.join(", ", names));
        List<String> github = strings(assignees.path("github"));
        if (!github.isEmpty()) body += "\n- GitHub: " + String.join(", ", github.stream().map(n -> "@" + n).toList());
        List<String> unresolved = strings(assignees.path("unresolved"));
        if (!unresolved.isEmpty()) body += "\n- GitHub 계정 미매핑: " + String.join(", ", unresolved);
        return body;
    }

    private static String dueBody(JsonNode due) {
        String raw = text(due.path("text"));
        String date = text(due.path("date"));
        if (date == null) return raw == null ? "없음" : "없음 (원문: " + raw + ")";
        String time = text(due.path("time"));
        String value = date + (time == null ? "" : " " + time);
        return value.equals(Objects.requireNonNullElse(raw, "")) ? value : value + " (원문: " + Objects.requireNonNullElse(raw, "") + ")";
    }

    private static Counts counts(List<DispatchTicket> tickets) {
        int pending = 0, linked = 0, assigneeRequired = 0;
        for (var t : tickets) {
            if (t.ticket().processingType() == ProcessingType.LINK) linked++;
            else if (t.ticket().status() == TicketStatus.PENDING_REVIEW) pending++;
            if (t.ticket().status() == TicketStatus.ASSIGNEE_REQUIRED) assigneeRequired++;
        }
        return new Counts(tickets.size(), pending, linked, assigneeRequired);
    }

    private static String text(JsonNode node) {
        return node.isNull() || node.isMissingNode() || node.asText("").isBlank() ? null : node.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> { if (text(n) != null) out.add(n.asText()); });
        return out;
    }

    public record AssignRequest(
            @Schema(description = "대표 담당으로 지정할 같은 프로젝트 팀원 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "11")
            @NotNull(message = "대표 담당을 선택해 주세요.") Long memberId) {}

    public record DispatchResult(
            @Schema(description = "분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81") UUID taskId,
            @Schema(description = "건수 요약", requiredMode = Schema.RequiredMode.REQUIRED) Counts counts,
            @Schema(description = "이 회의록에서 만든 티켓", requiredMode = Schema.RequiredMode.REQUIRED) List<DispatchTicket> tickets,
            @Schema(description = "로그인 사용자가 회의록을 올린 사람이라 대표 담당을 지정할 수 있는지", requiredMode = Schema.RequiredMode.REQUIRED, example = "true") boolean canAssign,
            @Schema(description = "대표 담당으로 고를 수 있는 프로젝트 팀원. canAssign이 false면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED) List<MemberOption> members) {}

    public record Counts(
            @Schema(description = "전체 티켓 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "9") int total,
            @Schema(description = "검토 대기 (기존 이슈 연결 제외)", requiredMode = Schema.RequiredMode.REQUIRED, example = "7") int pendingReview,
            @Schema(description = "기존 이슈 연결 (처리 유형 LINK)", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int linked,
            @Schema(description = "담당 지정 필요", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int assigneeRequired) {}

    public record DispatchTicket(
            @Schema(description = "티켓 (내 티켓 조회와 같은 형식). assignee가 받은 사람이다", requiredMode = Schema.RequiredMode.REQUIRED) MyTicketService.Ticket ticket,
            @Schema(description = "받은 사람이 로그인 사용자인지. 화면에 \"나\"로 표시", requiredMode = Schema.RequiredMode.REQUIRED, example = "false") boolean mine,
            @Schema(description = "GitHub 이슈 미리보기. GitHub 티켓만 있고, 나머지는 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) IssuePreview preview) {}

    public record IssuePreview(
            @Schema(description = "이슈 본문 Markdown (회의록 원문이 섞여 있으므로 HTML로 그릴 때 sanitize 필요)", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String body,
            @Schema(description = "라벨", requiredMode = Schema.RequiredMode.REQUIRED) List<String> labels,
            @Schema(description = "근거 (회의록 원문 인용)", requiredMode = Schema.RequiredMode.REQUIRED) List<String> evidenceQuotes) {}

    public record MemberOption(
            @Schema(description = "프로젝트 팀원 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "11") Long memberId,
            @Schema(description = "팀원 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "박종민") String name) {}
}
