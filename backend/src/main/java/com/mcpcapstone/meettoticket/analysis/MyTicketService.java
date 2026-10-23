package com.mcpcapstone.meettoticket.analysis;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 담당자가 자기 티켓을 프로젝트별로 본다 (REQ-013).
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyTicketService {
    private final TaskItemRepository items;

    public MyTickets list(Long userId, Long projectId) {
        List<TaskItem> found = items.findAssignedTo(userId, projectId);
        return new MyTickets(
                new Counts(count(found, TicketStatus.PENDING_REVIEW), count(found, TicketStatus.CONFIRMED), count(found, TicketStatus.REJECTED)),
                found.stream().map(MyTicketService::view).toList());
    }

    private static int count(List<TaskItem> list, TicketStatus status) {
        return (int) list.stream().filter(i -> i.getTicketStatus() == status).count();
    }

    private static Ticket view(TaskItem i) {
        var meeting = i.getTask().getMeeting();
        return new Ticket(
                i.getId(),
                i.getTitle(),
                i.getTicketStatus(),
                i.getProcessingType(),
                meeting.getProject().getId(),
                meeting.getProject().getName(),
                meeting.getId(),
                meeting.getTitle(),
                i.getTask().getId(),
                i.getTask().getCreatedAt());
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
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long projectId,
            @Schema(description = "프로젝트 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 내보내기") String projectName,
            @Schema(description = "회의 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2") Long meetingId,
            @Schema(description = "회의 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 중간 점검") String meetingTitle,
            @Schema(description = "분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81") UUID taskId,
            @Schema(description = "분석 접수 시각", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-22T10:31:00+09:00") OffsetDateTime createdAt) {}
}
