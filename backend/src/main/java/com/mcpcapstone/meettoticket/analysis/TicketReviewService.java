package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

// 담당자가 카드에서 티켓을 보고, 고치고, 확인·거절한다 (REQ-014, TASK-028·029).
// 카드에 보이는 값(고친 값이 있으면 고친 값)이 일괄 발행(TASK-034)이 쓸 값이다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketReviewService {
    private final TaskItemRepository items;
    private final ProjectMemberRepository members;
    private final JsonCodec json;

    // 기획 4.2 상태 전이. 이 표에 없는 전이는 409다.
    public enum Transition {
        CONFIRM(TicketStatus.PENDING_REVIEW, TicketStatus.CONFIRMED),
        REJECT(TicketStatus.PENDING_REVIEW, TicketStatus.REJECTED),
        REOPEN(TicketStatus.CONFIRMED, TicketStatus.PENDING_REVIEW),
        RESTORE(TicketStatus.REJECTED, TicketStatus.PENDING_REVIEW);

        private final TicketStatus from;
        private final TicketStatus to;

        Transition(TicketStatus from, TicketStatus to) {
            this.from = from;
            this.to = to;
        }
    }

    public TicketDetail detail(UUID id, Long userId) {
        return view(owned(items.findById(id), userId));
    }

    @Transactional
    public TicketDetail change(UUID id, Long userId, Transition transition) {
        TaskItem t = owned(items.findForUpdate(id), userId);
        if (t.getTicketStatus() != transition.from) throw new BusinessException(ErrorCode.TICKET_STATE_CONFLICT);
        t.changeStatus(transition.to);
        return view(t);
    }

    // 보내지 않은 칸은 그대로 둔다. 검토 대기에서만 고칠 수 있다.
    // 담당자를 다른 멤버로 바꾸면 그 멤버의 내 티켓으로 옮겨 가고, 응답은 바뀐 티켓을 그대로 돌려준다.
    @Transactional
    public TicketDetail edit(UUID id, Long userId, TicketEditRequest request) {
        TaskItem t = owned(items.findForUpdate(id), userId);
        if (t.getTicketStatus() != TicketStatus.PENDING_REVIEW) throw new BusinessException(ErrorCode.TICKET_STATE_CONFLICT);

        String title = t.getTitle();
        if (request.title() != null) {
            if (request.title().isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            title = request.title().strip();
        }
        ObjectNode edited = t.getEditedJson() == null
                ? (ObjectNode) json.tree(Map.of())
                : (ObjectNode) json.read(t.getEditedJson());
        if (request.body() != null) edited.put("body", request.body());
        if (request.due() != null) {
            DueInput due = request.due();
            // 시각만 있는 기한은 받지 않는다. 날짜를 비우면 기한 없음이다
            if (due.date() == null && due.time() != null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            ObjectNode node = edited.putObject("due");
            if (due.date() == null) node.putNull("date");
            else node.put("date", due.date().toString());
            if (due.time() == null) node.putNull("time");
            else node.put("time", due.time().toString());
        }
        ProjectMember assignee = t.getAssignee();
        if (request.assigneeMemberId() != null) {
            Long projectId = t.getTask().getMeeting().getProject().getId();
            assignee = members.findById(request.assigneeMemberId())
                    .filter(m -> m.getProject().getId().equals(projectId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TICKET_ASSIGNEE));
        }
        t.edit(title, edited.isEmpty() ? null : json.write(edited), assignee);
        return view(t);
    }

    // 담당자만 볼 수 있다. 담당 지정 필요 티켓은 담당자가 없어 아무도 열 수 없다 (대표 담당 지정은 TASK-025).
    private static TaskItem owned(Optional<TaskItem> found, Long userId) {
        TaskItem t = found.orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
        var user = t.getAssignee() == null ? null : t.getAssignee().getUser();
        if (user == null || !user.getId().equals(userId)) throw new BusinessException(ErrorCode.TICKET_ACCESS_DENIED);
        return t;
    }

    // Agent 결과(contentJson)와 고친 값을 합쳐 카드에 보일 값을 만든다.
    // 실제 Agent와 모의 실행의 키가 달라 둘 다 읽는다 (프론트 TaskItemContent와 같은 기준).
    private TicketDetail view(TaskItem t) {
        JsonNode c = json.read(t.getContentJson());
        JsonNode e = t.getEditedJson() == null ? json.tree(Map.of()) : json.read(t.getEditedJson());
        var meeting = t.getTask().getMeeting();
        ProjectMember m = t.getAssignee();

        String body = e.has("body") ? e.path("body").asText("")
                : first(c, "issue_body", "description", "background");
        JsonNode d = e.has("due") ? e.path("due") : c.path("due");
        Due due = new Due(text(d.path("date")), text(d.path("time")),
                // 고친 기한에는 원문 표현이 없다
                e.has("due") ? null : first(d, "text", "originalText"));

        List<String> mentioned = new ArrayList<>();
        JsonNode assignees = c.path("assignees");
        if (assignees.isArray()) assignees.forEach(a -> add(mentioned, a.path("mentionedName")));
        else assignees.path("names").forEach(n -> add(mentioned, n));

        List<String> evidence = strings(c.path("evidence_quotes"));
        if (evidence.isEmpty()) c.path("evidence").forEach(q -> add(evidence, q.path("quote")));

        return new TicketDetail(
                t.getId(),
                t.getTitle(),
                body,
                t.getTicketStatus(),
                t.getProcessingType(),
                m == null ? null : new Assignee(m.getId(), m.getName(), m.getGithubName()),
                mentioned,
                due,
                strings(c.path("labels")),
                evidence,
                strings(c.path("open_questions")),
                strings(c.path("warnings")),
                c.path("dedupe").isObject() ? c.path("dedupe") : null,
                t.getEditedJson() != null,
                meeting.getProject().getId(),
                meeting.getProject().getName(),
                meeting.getId(),
                meeting.getTitle(),
                t.getTask().getId(),
                t.getTask().getCreatedAt());
    }

    private static String first(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = text(node.path(k));
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }

    private static String text(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> add(out, n));
        return out;
    }

    private static void add(List<String> list, JsonNode node) {
        String v = text(node);
        if (v != null && !v.isBlank()) list.add(v);
    }

    public record TicketEditRequest(
            @Schema(description = "제목. 보내지 않으면 그대로", example = "CSV 빈 결과 오류 수정", nullable = true)
            @Size(max = 255, message = "제목은 255자 이하로 입력해 주세요.")
            String title,
            @Schema(description = "이슈 본문(Markdown). 보내지 않으면 그대로", example = "## 작업 내용\n필터 결과가 0건이면 헤더만 있는 CSV를 내려준다.", nullable = true)
            @Size(max = 20000, message = "본문은 20000자 이하로 입력해 주세요.")
            String body,
            @Schema(description = "담당 멤버 ID (같은 프로젝트 멤버). 보내지 않으면 그대로", example = "3", nullable = true)
            Long assigneeMemberId,
            @Schema(description = "기한. 보내지 않으면 그대로, date를 null로 보내면 기한 없음", nullable = true)
            DueInput due) {}

    public record DueInput(
            @Schema(description = "기한 날짜. null이면 기한 없음", example = "2026-10-25", nullable = true) LocalDate date,
            @Schema(description = "기한 시각. 날짜가 있을 때만", example = "18:00", nullable = true) LocalTime time) {}

    public record Assignee(
            @Schema(description = "프로젝트 멤버 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "3") Long memberId,
            @Schema(description = "멤버 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "박서연") String name,
            @Schema(description = "GitHub 계정. 없으면 null (이슈에 이름만 적음)", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "seoyeon-p") String githubName) {}

    public record Due(
            @Schema(description = "기한 날짜 YYYY-MM-DD. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-10-25") String date,
            @Schema(description = "기한 시각 HH:mm. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "18:00") String time,
            @Schema(description = "회의록의 기한 원문 표현. 담당자가 기한을 고쳤거나 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "내일 오후 6시") String text) {}

    public record TicketDetail(
            @Schema(description = "티켓 ID (결과 항목 itemId와 같음)", requiredMode = Schema.RequiredMode.REQUIRED, example = "3c510e28-c776-43ce-85cc-6c90693d31f5") UUID ticketId,
            @Schema(description = "제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 빈 결과 오류 수정") String title,
            @Schema(description = "이슈 본문(Markdown). 고친 본문이 있으면 그 값. 미리보기에서 HTML로 그릴 때는 sanitize 필요", requiredMode = Schema.RequiredMode.REQUIRED) String body,
            @Schema(description = "티켓 상태", requiredMode = Schema.RequiredMode.REQUIRED, example = "PENDING_REVIEW") TicketStatus status,
            @Schema(description = "처리 유형. 신규 발행 CREATE, 기존 이슈 연결 LINK, 갱신 UPDATE", requiredMode = Schema.RequiredMode.REQUIRED, example = "CREATE") ProcessingType processingType,
            @Schema(description = "담당 멤버", requiredMode = Schema.RequiredMode.REQUIRED) Assignee assignee,
            @Schema(description = "회의록에 적힌 담당자 이름", requiredMode = Schema.RequiredMode.REQUIRED) List<String> mentionedAssignees,
            @Schema(description = "기한", requiredMode = Schema.RequiredMode.REQUIRED) Due due,
            @Schema(description = "라벨", requiredMode = Schema.RequiredMode.REQUIRED) List<String> labels,
            @Schema(description = "근거 (회의록 원문 인용)", requiredMode = Schema.RequiredMode.REQUIRED) List<String> evidenceQuotes,
            @Schema(description = "이 할 일과 관련된 미결 질문", requiredMode = Schema.RequiredMode.REQUIRED) List<String> openQuestions,
            @Schema(description = "카드 경고 (원문과 다른 기한, 남은 검수 지적 등)", requiredMode = Schema.RequiredMode.REQUIRED) List<String> warnings,
            @Schema(description = "중복 판단 결과. 판단하지 않았으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, type = "object", implementation = Object.class) JsonNode dedupe,
            @Schema(description = "담당자가 본문이나 기한을 고쳤는지", requiredMode = Schema.RequiredMode.REQUIRED, example = "false") boolean edited,
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long projectId,
            @Schema(description = "프로젝트 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 내보내기") String projectName,
            @Schema(description = "회의 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2") Long meetingId,
            @Schema(description = "회의 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 중간 점검") String meetingTitle,
            @Schema(description = "분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81") UUID taskId,
            @Schema(description = "분석 접수 시각", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-22T10:31:00+09:00") OffsetDateTime createdAt) {}
}
