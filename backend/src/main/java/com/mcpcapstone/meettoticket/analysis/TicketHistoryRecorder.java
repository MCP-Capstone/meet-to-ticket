package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.common.JsonCodec;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 티켓 리비전(task_item_revisions)과 상태 이력(ticket_status_history)을 쌓는다 (#264).
// 호출한 쪽의 트랜잭션에 참여하므로, 상태나 내용이 저장될 때만 이력도 함께 남는다.
// 티켓은 영속 상태여야 한다 (새 티켓은 저장한 뒤 돌려받은 객체를 넘긴다).
@Service
@RequiredArgsConstructor
public class TicketHistoryRecorder {
    // 전이 원인 코드. 사용자 동작은 TicketService.Action 이름(CONFIRM, REJECT, REOPEN, RESTORE, REVISE)을 그대로 쓴다.
    public static final String REASON_AGENT_RESULT = "AGENT_RESULT";
    public static final String REASON_ASSIGN = "ASSIGN";
    public static final String REASON_PUBLISH_REQUESTED = "PUBLISH_REQUESTED";
    public static final String REASON_PUBLISH_NOT_ACCEPTED = "PUBLISH_NOT_ACCEPTED";
    public static final String REASON_PUBLISH_RESULT = "PUBLISH_RESULT";
    public static final String REASON_OUTCOME_VERIFIED = "OUTCOME_VERIFIED";

    // 사용자가 고칠 수 있는 값. 이 값이 직전 리비전과 다르면 수정본이다 (result는 비교하지 않는다).
    private static final List<String> EDITABLE = List.of(
            "title", "assignee_member_id", "publish_target", "processing_type", "content");

    private final TaskItemRevisionRepository revisions;
    private final TicketStatusHistoryRepository history;
    private final JsonCodec json;

    // Agent 결과로 티켓이 만들어졌을 때: AGENT 리비전 1건과 상태 이력의 시작(from=null).
    @Transactional
    public void created(TaskItem item) {
        revisions.save(new TaskItemRevision(item, 1, RevisionKind.AGENT, json.write(snapshot(item)), null));
        history.save(new TicketStatusHistory(item, null, item.getTicketStatus(), null, REASON_AGENT_RESULT));
    }

    // 상태가 from에서 지금 상태로 바뀐 직후에 부른다. 바뀌지 않았으면 남기지 않는다.
    @Transactional
    public void statusChanged(TaskItem item, TicketStatus from, Long actorUserId, String reason) {
        if (from == item.getTicketStatus()) return;
        history.save(new TicketStatusHistory(item, from, item.getTicketStatus(), actorUserId, reason));
    }

    // 담당자가 확정하는 시점에 부른다. 직전 리비전과 달라진 값이 있을 때만 USER_EDIT 1건을 쌓는다.
    // 카드 수정(PATCH)을 할 때마다 쌓지 않고, 확정 시점의 최종 편집본만 남긴다. 고친 것이 없으면 쌓지 않는다.
    // 비교 기준(직전 리비전)이 없는 티켓은 수정 여부를 알 수 없어 쌓지 않는다.
    // 반환값은 쌓았는지 여부.
    @Transactional
    public boolean userEdited(TaskItem item, Long actorUserId) {
        var last = revisions.findFirstByTaskItem_IdOrderByRevDesc(item.getId());
        if (last.isEmpty()) return false;
        Map<String, Object> current = snapshot(item);
        String serialized = json.write(current);
        // 저장된 값과 같은 방식으로 읽어 비교한다 (tree()로 바로 만들면 숫자 노드 타입이 달라 같은 값도 다르게 나온다).
        JsonNode before = json.read(last.get().getSnapshot());
        JsonNode after = json.read(serialized);
        boolean changed = EDITABLE.stream().anyMatch(key -> !before.path(key).equals(after.path(key)));
        if (!changed) return false;
        revisions.save(new TaskItemRevision(
                item, last.get().getRev() + 1, RevisionKind.USER_EDIT, serialized, actorUserId));
        return true;
    }

    // 발행이 PUBLISHED, LINKED, UPDATED로 확정됐을 때 부른다. 발행 결과를 담은 최종본을 티켓당 1건만 쌓는다.
    // SKIPPED(발행 생략)와 실패는 부르지 않는다.
    @Transactional
    public void published(TaskItem item, Long actorUserId) {
        if (revisions.existsByTaskItem_IdAndKind(item.getId(), RevisionKind.PUBLISHED)) return;
        Map<String, Object> snapshot = snapshot(item);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", item.getTicketStatus());
        result.put("processing_type", item.getProcessingType());
        result.put("github_issue_number", item.getGithubIssueNumber());
        result.put("url", item.getGithubIssueUrl());
        result.put("error_code", item.getPublishErrorCode());
        snapshot.put("result", result);
        int rev = revisions.findFirstByTaskItem_IdOrderByRevDesc(item.getId()).map(r -> r.getRev() + 1).orElse(1);
        revisions.save(new TaskItemRevision(item, rev, RevisionKind.PUBLISHED, json.write(snapshot), actorUserId));
    }

    // 티켓 전체 스냅샷 (schema_version 1). 키는 SQL로 꺼내기 쉽게 snake_case로 둔다.
    private Map<String, Object> snapshot(TaskItem item) {
        var assignee = item.getAssignee();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("title", item.getTitle());
        snapshot.put("assignee_member_id", assignee == null ? null : assignee.getId());
        snapshot.put("assignee_name", assignee == null ? null : assignee.getName());
        snapshot.put("publish_target", item.getPublishTarget());
        snapshot.put("processing_type", item.getProcessingType());
        snapshot.put("content", json.read(item.getContentJson()));
        return snapshot;
    }
}
