package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

// 티켓의 에이전트 원본/수정본/발행본 리비전과 상태 이력, 승인 이력 조회 (#264). 읽기 전용이다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketHistoryService {
    private final TaskItemRepository items;
    private final TaskItemRevisionRepository revisions;
    private final TicketStatusHistoryRepository history;
    private final UserRepository users;
    private final JsonCodec json;

    // 티켓이 없으면 404, 담당자나 회의록을 올린 사람이 아니면 403.
    public TicketHistory get(UUID ticketId, Long userId) {
        TaskItem item = items.findById(ticketId).orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
        if (!canView(item, userId)) throw new BusinessException(ErrorCode.TICKET_HISTORY_FORBIDDEN);

        List<TaskItemRevision> revs = revisions.findByTaskItem_IdOrderByRev(ticketId);
        List<TicketStatusHistory> changes = history.findByTaskItem_IdOrderByCreatedAtAscIdAsc(ticketId);
        Map<Long, Actor> actors = actors(revs, changes);

        // 지금 상태로 이어진 마지막 확정(CONFIRMED로 승인)을 승인 이력으로 준다. 다시 열었다 확정하면 마지막 확정이 기준이다.
        Approval approval = changes.stream()
                .filter(c -> c.getToStatus() == TicketStatus.CONFIRMED)
                .reduce((first, second) -> second)
                .map(c -> new Approval(actors.get(c.getActorUserId()), c.getCreatedAt()))
                .orElse(null);
        return new TicketHistory(
                ticketId,
                revs.stream().map(r -> new Revision(
                        r.getRev(), r.getKind(), json.read(r.getSnapshot()), r.getSchemaVersion(),
                        r.getPromptVersion(), r.getModel(), actors.get(r.getActorUserId()), r.getCreatedAt())).toList(),
                changes.stream().map(c -> new StatusChange(
                        c.getFromStatus(), c.getToStatus(), actors.get(c.getActorUserId()), c.getReason(), c.getCreatedAt())).toList(),
                approval);
    }

    private static boolean canView(TaskItem item, Long userId) {
        var assignee = item.getAssignee();
        if (assignee != null && assignee.getUser() != null && assignee.getUser().getId().equals(userId)) return true;
        var uploader = item.getTask().getMeeting().getCreatedBy();
        return uploader != null && uploader.getId().equals(userId);
    }

    private Map<Long, Actor> actors(List<TaskItemRevision> revs, List<TicketStatusHistory> changes) {
        List<Long> ids = Stream.concat(
                        revs.stream().map(TaskItemRevision::getActorUserId),
                        changes.stream().map(TicketStatusHistory::getActorUserId))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Actor> found = new HashMap<>();
        for (User u : users.findAllById(ids)) found.put(u.getId(), new Actor(u.getId(), u.getName()));
        // 계정이 없어도 id는 남긴다
        for (Long id : ids) found.putIfAbsent(id, new Actor(id, null));
        return found;
    }

    public record TicketHistory(
            @Schema(description = "티켓 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "3c510e28-c776-43ce-85cc-6c90693d31f5") UUID ticketId,
            @Schema(description = "리비전. rev 오름차순. AGENT(에이전트 원본), USER_EDIT(확정한 수정본, 바뀐 것이 있을 때만), PUBLISHED(발행 성공 최종본)", requiredMode = Schema.RequiredMode.REQUIRED) List<Revision> revisions,
            @Schema(description = "상태 이력. 시간 오름차순. 이 기능이 들어오기 전에 만들어진 티켓은 비어 있거나 이후 전이만 있다", requiredMode = Schema.RequiredMode.REQUIRED) List<StatusChange> statusHistory,
            @Schema(description = "마지막으로 CONFIRMED로 승인한 사람과 시각. 승인한 적이 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Approval approval) {}

    public record Revision(
            @Schema(description = "티켓 안의 번호. 1부터", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int rev,
            @Schema(description = "종류", requiredMode = Schema.RequiredMode.REQUIRED, example = "AGENT") RevisionKind kind,
            @Schema(description = "그 시점의 전체 스냅샷 (title, assignee_member_id, assignee_name, publish_target, processing_type, content, PUBLISHED는 result). 형식은 schemaVersion으로 구분", requiredMode = Schema.RequiredMode.REQUIRED, type = "object", implementation = Object.class) JsonNode snapshot,
            @Schema(description = "스냅샷 형식 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1") int schemaVersion,
            @Schema(description = "에이전트 프롬프트 버전. 아직 기록하지 않아 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String promptVersion,
            @Schema(description = "에이전트 모델. 아직 기록하지 않아 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String model,
            @Schema(description = "수정/발행한 사용자. AGENT 리비전은 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Actor actor,
            @Schema(description = "만든 시각. V4 이전 티켓의 AGENT 리비전은 분석을 마친 시각", requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt) {}

    public record StatusChange(
            @Schema(description = "이전 상태. 티켓이 처음 만들어질 때는 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "PENDING_REVIEW") TicketStatus from,
            @Schema(description = "바뀐 상태", requiredMode = Schema.RequiredMode.REQUIRED, example = "CONFIRMED") TicketStatus to,
            @Schema(description = "바꾼 사용자. 에이전트 결과, 발행 결과 반영처럼 시스템이 바꾼 경우는 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Actor actor,
            @Schema(description = "원인 코드. 사용자 동작은 CONFIRM, REJECT, REOPEN, RESTORE, REVISE, ASSIGN, OUTCOME_VERIFIED, 시스템은 AGENT_RESULT, PUBLISH_REQUESTED, PUBLISH_NOT_ACCEPTED, PUBLISH_RESULT, 발행 실패는 오류 코드", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "CONFIRM") String reason,
            @Schema(description = "바뀐 시각", requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt) {}

    public record Approval(
            @Schema(description = "승인한 사용자", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Actor actor,
            @Schema(description = "승인한 시각", requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime approvedAt) {}

    public record Actor(
            @Schema(description = "사용자 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "3") Long userId,
            @Schema(description = "사용자 이름. 계정을 찾을 수 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "박종민") String name) {}
}
