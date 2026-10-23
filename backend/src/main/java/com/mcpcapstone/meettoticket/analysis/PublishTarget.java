package com.mcpcapstone.meettoticket.analysis;

// 발행 대상 (REQ-015, REQ-021). Agent 분류(TASK-020)가 결과에 담기 전까지 GitHub로 둔다.
public enum PublishTarget {
    GITHUB,
    SLACK,
    NOTION
}
