package com.mcpcapstone.meettoticket.analysis;

// task_item_revisions.kind. 티켓당 AGENT 1건, 확정 시 바뀐 내용이 있으면 USER_EDIT, 발행 성공 시 PUBLISHED 1건.
public enum RevisionKind {
    AGENT, // 에이전트가 만든 원본
    USER_EDIT, // 담당자가 확정한 수정본
    PUBLISHED // 발행에 성공한 최종본
}
