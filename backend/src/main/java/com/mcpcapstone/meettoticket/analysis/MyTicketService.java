package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.common.JsonCodec;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 담당자가 자기 티켓을 프로젝트별로 본다 (REQ-013).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyTicketService {
    private final TaskItemRepository items;
    private final JsonCodec json;

    public MyTickets list(Long userId, Long projectId) {
        List<TaskItem> found = items.findAssignedTo(userId, projectId);
        return new MyTickets(
                new Counts(count(found, TicketStatus.PENDING_REVIEW), count(found, TicketStatus.CONFIRMED), count(found, TicketStatus.REJECTED)),
                found.stream().map(this::view).toList());
    }

    private static int count(List<TaskItem> list, TicketStatus status) {
        return (int) list.stream().filter(i -> i.getTicketStatus() == status).count();
    }

    public Ticket view(TaskItem i) {
        var meeting = i.getTask().getMeeting();
        JsonNode content = json.read(i.getContentJson());
        JsonNode due = content.path("due");
        var assignee = i.getAssignee();
        return new Ticket(
                i.getId(),
                i.getTitle(),
                i.getTicketStatus(),
                i.getProcessingType(),
                i.getPublishTarget(),
                dedupe(content.path("dedupe"), i, meeting.getProject()),
                meeting.getProject().getId(),
                meeting.getProject().getName(),
                meeting.getId(),
                meeting.getTitle(),
                i.getTask().getId(),
                i.getTask().getCreatedAt(),
                content.path("description").asText(null),
                new Due(blankToNull(due.path("text").asText(null)), date(due.path("date").asText(null)), blankToNull(due.path("time").asText(null))),
                assignee == null ? null : new Assignee(assignee.getId(), assignee.getName()),
                i.getNotionPageId(),
                i.getNotionPageUrl());
    }

    // 카드가 그릴 중복 판단 결과 (REQ-026). 판단하지 않았거나 건너뛰었으면 judged=false.
    private static Dedupe dedupe(JsonNode d, TaskItem i, com.mcpcapstone.meettoticket.project.Project project) {
        String relation = blankToNull(d.path("relation").asText(null));
        Integer number = d.path("issue_number").isIntegralNumber() ? d.path("issue_number").intValue() : null;
        if (relation == null)
            return new Dedupe(false, blankToNull(d.path("skipped").asText(null)), null, null, false, null, null, null, false);
        ExistingIssue issue = number == null ? null : new ExistingIssue(
                number,
                blankToNull(d.path("issue_title").asText(null)),
                blankToNull(d.path("issue_state").asText(null)),
                "https://github.com/%s/%s/issues/%d".formatted(project.getGithubOwner(), project.getGithubRepo(), number));
        // 새로 발행하는데 같은 작업이나 관련 이슈가 있으면 겹칠 수 있다 (닫힌 SAME/CHANGE, RELATED, 담당자가 신규로 바꾼 연결/갱신)
        boolean overlap = issue != null && !"UNRELATED".equals(relation) && i.getProcessingType() == ProcessingType.CREATE;
        return new Dedupe(true, null, relation, issue, overlap,
                blankToNull(d.path("reason").asText(null)),
                blankToNull(d.path("before").asText(null)),
                blankToNull(d.path("after").asText(null)),
                d.path("handling").path("changed").asBoolean(false));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static LocalDate date(String v) {
        try {
            return v == null || v.isBlank() ? null : LocalDate.parse(v);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    public record MyTickets(
            @Schema(description = "조회 범위(프로젝트 필터 적용)의 상태별 건수", requiredMode = Schema.RequiredMode.REQUIRED) Counts counts,
            @Schema(description = "내가 담당인 티켓. 최근 분석 순", requiredMode = Schema.RequiredMode.REQUIRED) List<Ticket> tickets) {}

    public record Counts(
            @Schema(description = "검토 대기", requiredMode = Schema.RequiredMode.REQUIRED, example = "3") int pendingReview,
            @Schema(description = "확인", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int confirmed,
            @Schema(description = "거절", requiredMode = Schema.RequiredMode.REQUIRED, example = "0") int rejected) {}

    public record Ticket(
            @Schema(description = "티켓 ID (결과 항목 itemId와 같음)", requiredMode = Schema.RequiredMode.REQUIRED, example = "3c510e28-c776-43ce-85cc-6c90693d31f5") UUID ticketId,
            @Schema(description = "티켓 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 빈 결과 오류 수정") String title,
            @Schema(description = "티켓 상태", requiredMode = Schema.RequiredMode.REQUIRED, example = "PENDING_REVIEW") TicketStatus status,
            @Schema(description = "처리 유형. 신규 발행 CREATE, 기존 이슈 연결 LINK, 갱신 UPDATE", requiredMode = Schema.RequiredMode.REQUIRED, example = "CREATE") ProcessingType processingType,
            @Schema(description = "발행 대상. GitHub 이외는 처리 유형이 항상 CREATE", requiredMode = Schema.RequiredMode.REQUIRED, example = "GITHUB") PublishTarget publishTarget,
            @Schema(description = "기존 이슈 중복 판단 결과 (읽기 전용)", requiredMode = Schema.RequiredMode.REQUIRED) Dedupe dedupe,
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long projectId,
            @Schema(description = "프로젝트 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 내보내기") String projectName,
            @Schema(description = "회의 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2") Long meetingId,
            @Schema(description = "회의 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 중간 점검") String meetingTitle,
            @Schema(description = "분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81") UUID taskId,
            @Schema(description = "분석 접수 시각", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-22T10:31:00+09:00") OffsetDateTime createdAt,
            @Schema(description = "티켓 본문(작업 내용). 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "CSV 내보내기에서 결과가 비면 오류가 난다.") String description,
            @Schema(description = "기한", requiredMode = Schema.RequiredMode.REQUIRED) Due due,
            @Schema(description = "담당 멤버. 담당 지정 필요 상태면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Assignee assignee,
            @Schema(description = "실제 생성된 Notion 페이지 ID. Notion 발행 전 또는 GitHub 대상이면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String notionPageId,
            @Schema(description = "실제 생성된 Notion 페이지 URL. Notion 발행 전 또는 GitHub 대상이면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String notionPageUrl) {}

    public record Dedupe(
            @Schema(description = "중복 판단을 했는지. false면 카드에 \"중복 판단 안 됨\"을 표시", requiredMode = Schema.RequiredMode.REQUIRED, example = "true") boolean judged,
            @Schema(description = "판단하지 못한 이유 (timeout, tool_limit 등). 판단했으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String skipped,
            @Schema(description = "기존 이슈와의 관계", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, allowableValues = {"SAME", "CHANGE", "RELATED", "UNRELATED"}, example = "RELATED") String relation,
            @Schema(description = "관계가 있는 기존 이슈. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ExistingIssue issue,
            @Schema(description = "\"겹칠 수 있음\" 경고. 신규 발행인데 같은 작업이나 관련 이슈가 있으면 true", requiredMode = Schema.RequiredMode.REQUIRED, example = "true") boolean overlap,
            @Schema(description = "판단 사유", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "같은 CSV 화면이지만 빈 결과 처리를 더 요구함") String reason,
            @Schema(description = "갱신(CHANGE)의 변경 전 값", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "동기 처리") String before,
            @Schema(description = "갱신(CHANGE)의 변경 후 값", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "비동기 처리") String after,
            @Schema(description = "발행 직전 재대조로 처리 유형이 연결로 바뀌었는지", requiredMode = Schema.RequiredMode.REQUIRED, example = "false") boolean changedByRecheck) {}

    public record ExistingIssue(
            @Schema(description = "기존 이슈 번호", requiredMode = Schema.RequiredMode.REQUIRED, example = "12") int number,
            @Schema(description = "기존 이슈 제목", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "CSV 빈 결과 오류 수정") String title,
            @Schema(description = "기존 이슈 상태", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, allowableValues = {"open", "closed"}, example = "open") String state,
            @Schema(description = "기존 이슈 링크", requiredMode = Schema.RequiredMode.REQUIRED, example = "https://github.com/MCP-Capstone/mcp-capstone/issues/12") String url) {}

    public record Due(
            @Schema(description = "기한 원문 표현", nullable = true, example = "내일 오후 6시") String text,
            @Schema(description = "기한 날짜", nullable = true, example = "2026-10-23") LocalDate date,
            @Schema(description = "기한 시각 HH:mm", nullable = true, example = "18:00") String time) {}

    public record Assignee(
            @Schema(description = "프로젝트 팀원 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "11") Long projectMemberId,
            @Schema(description = "팀원 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "박종민") String name) {}
}
