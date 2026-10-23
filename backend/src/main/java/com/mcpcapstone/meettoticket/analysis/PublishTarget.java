package com.mcpcapstone.meettoticket.analysis;

import tools.jackson.databind.JsonNode;

// 발행 대상 (REQ-015, REQ-021). Agent가 분류한 값(contentJson.publish_target)으로 시작하고, 담당자가 카드에서 바꿀 수 있다.
public enum PublishTarget {
    GITHUB,
    SLACK,
    NOTION;

    // 값이 없거나 모르는 값이면 GitHub로 둔다 (분류 전 Agent 결과, 모의 실행).
    public static PublishTarget from(JsonNode content) {
        String value = content == null ? "" : content.path("publish_target").asText("");
        for (PublishTarget target : values())
            if (target.name().equals(value)) return target;
        return GITHUB;
    }
}
