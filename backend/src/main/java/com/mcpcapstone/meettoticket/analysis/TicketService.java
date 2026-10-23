package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.*;

// 담당자가 카드에서 티켓을 고치고 확인/거절한다 (REQ-014, 기획 4.2).
@Service
@RequiredArgsConstructor
public class TicketService {
    private final TaskItemRepository items;
    private final ProjectMemberRepository members;
    private final MyTicketService view;
    private final JsonCodec json;

    // 상태 전이. 이 표 밖의 전이는 409다.
    private enum Action {
        CONFIRM(TicketStatus.PENDING_REVIEW, TicketStatus.CONFIRMED),
        REJECT(TicketStatus.PENDING_REVIEW, TicketStatus.REJECTED),
        REOPEN(TicketStatus.CONFIRMED, TicketStatus.PENDING_REVIEW),
        RESTORE(TicketStatus.REJECTED, TicketStatus.PENDING_REVIEW);

        final TicketStatus from;
        final TicketStatus to;

        Action(TicketStatus from, TicketStatus to) {
            this.from = from;
            this.to = to;
        }
    }

    @Transactional
    public MyTicketService.Ticket confirm(UUID id, Long userId) { return move(id, userId, Action.CONFIRM); }

    @Transactional
    public MyTicketService.Ticket reject(UUID id, Long userId) { return move(id, userId, Action.REJECT); }

    @Transactional
    public MyTicketService.Ticket reopen(UUID id, Long userId) { return move(id, userId, Action.REOPEN); }

    @Transactional
    public MyTicketService.Ticket restore(UUID id, Long userId) { return move(id, userId, Action.RESTORE); }

    private MyTicketService.Ticket move(UUID id, Long userId, Action action) {
        TaskItem item = ownedBy(id, userId);
        if (item.getTicketStatus() != action.from) throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);
        item.moveTo(action.to);
        return view.view(item);
    }

    // 검토 대기에서만 고칠 수 있다. 보내지 않은 항목은 그대로 둔다.
    @Transactional
    public MyTicketService.Ticket edit(UUID id, Long userId, EditRequest request) {
        if (request.description() == null && request.assigneeMemberId() == null && request.due() == null)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        TaskItem item = ownedBy(id, userId);
        if (item.getTicketStatus() != TicketStatus.PENDING_REVIEW) throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);

        ObjectNode content = (ObjectNode) json.read(item.getContentJson());
        if (request.description() != null) content.put("description", request.description().trim());
        if (request.due() != null) {
            var due = request.due();
            content.set("due", json.tree(Map.of(
                    "text", due.text() == null ? "" : due.text().trim(),
                    "date", due.date() == null ? "" : due.date().toString(),
                    "time", due.time() == null ? "" : due.time())));
        }
        ProjectMember newAssignee = null;
        if (request.assigneeMemberId() != null) {
            Long projectId = item.getTask().getMeeting().getProject().getId();
            newAssignee = members.findById(request.assigneeMemberId())
                    .filter(m -> m.getProject().getId().equals(projectId))
                    .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_MEMBER_NOT_FOUND));
            // 이름만 보고 GitHub 로그인명을 추정하지 않는다. 멤버에 저장된 계정만 쓴다.
            List<String> github = newAssignee.getGithubName() == null ? List.of() : List.of(newAssignee.getGithubName());
            content.set("assignees", json.tree(Map.of(
                    "names", List.of(newAssignee.getName()),
                    "github", github,
                    "unresolved", github.isEmpty() ? List.of(newAssignee.getName()) : List.of())));
        }
        item.edit(newAssignee, json.write(content));
        return view.view(item);
    }

    // 발행 대상과 처리 유형을 바꾼다 (REQ-015). Agent의 판단은 제안이고, 틀렸을 때 담당자가 여기서 구제한다 (기획 1.7.3).
    // 보내지 않은 항목은 그대로 둔다. 발행 대상을 GitHub 이외로 바꾸면 처리 유형은 신규 발행으로 고정한다.
    @Transactional
    public MyTicketService.Ticket changePublication(UUID id, Long userId, PublicationRequest request) {
        if (request.publishTarget() == null && request.processingType() == null)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        TaskItem item = ownedBy(id, userId);
        if (item.getTicketStatus() != TicketStatus.PENDING_REVIEW) throw new BusinessException(ErrorCode.TICKET_STATUS_CONFLICT);

        PublishTarget target = request.publishTarget() != null ? request.publishTarget() : item.getPublishTarget();
        ProcessingType type = request.processingType() != null ? request.processingType() : item.getProcessingType();
        if (target != PublishTarget.GITHUB) {
            if (request.processingType() != null && type != ProcessingType.CREATE)
                throw new BusinessException(ErrorCode.PROCESSING_TYPE_TARGET_MISMATCH);
            type = ProcessingType.CREATE;
        }
        // 연결과 갱신은 중복 판단이 찾은 열린 기존 이슈에만 한다. 닫힌 이슈는 다시 열지 않는다 (기획 1.6)
        if (type != ProcessingType.CREATE) {
            var dedupe = json.read(item.getContentJson()).path("dedupe");
            if (!dedupe.path("issue_number").isIntegralNumber() || !"open".equals(dedupe.path("issue_state").asText()))
                throw new BusinessException(ErrorCode.EXISTING_ISSUE_REQUIRED);
        }
        item.changePublication(target, type);
        return view.view(item);
    }

    // 티켓이 없으면 404, 담당자가 아니면 403.
    private TaskItem ownedBy(UUID id, Long userId) {
        TaskItem item = items.findForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
        ProjectMember assignee = item.getAssignee();
        if (assignee == null || assignee.getUser() == null || !assignee.getUser().getId().equals(userId))
            throw new BusinessException(ErrorCode.TICKET_ASSIGNEE_REQUIRED);
        return item;
    }

    public record PublicationRequest(
            @Schema(description = "바꿀 발행 대상. 보내지 않으면 그대로", nullable = true, example = "GITHUB") PublishTarget publishTarget,
            @Schema(description = "바꿀 처리 유형. 보내지 않으면 그대로. GitHub 이외 대상은 CREATE만 된다", nullable = true, example = "CREATE")
            ProcessingType processingType) {}

    public record EditRequest(
            @Schema(description = "고칠 본문(작업 내용). 보내지 않으면 그대로", nullable = true, example = "CSV 내보내기에서 결과가 비면 오류가 난다.")
            @Size(max = 4000) @Pattern(regexp = "(?s).*\\S.*", message = "본문은 비울 수 없습니다.") String description,
            @Schema(description = "바꿀 담당 멤버의 프로젝트 팀원 ID. 같은 프로젝트 멤버여야 한다", nullable = true, example = "12")
            Long assigneeMemberId,
            @Schema(description = "고칠 기한. 보내면 text, date, time 전체를 이 값으로 바꾼다", nullable = true)
            @Valid Due due) {}

    public record Due(
            @Schema(description = "기한 원문 표현", nullable = true, example = "금요일까지") @Size(max = 200) String text,
            @Schema(description = "기한 날짜", nullable = true, example = "2026-10-23") LocalDate date,
            @Schema(description = "기한 시각 HH:mm", nullable = true, example = "18:00")
            @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "HH:mm 형식이어야 합니다.") String time) {}
}
