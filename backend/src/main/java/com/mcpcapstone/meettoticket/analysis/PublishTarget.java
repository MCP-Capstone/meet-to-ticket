package com.mcpcapstone.meettoticket.analysis;

import tools.jackson.databind.JsonNode;

// 발행 대상 (REQ-015, REQ-021). Agent가 분류한 값(contentJson.publish_target)으로 시작하고, 담당자가 카드에서 바꿀 수 있다.
public enum PublishTarget {
    GITHUB(true),
    SLACK(false),
    NOTION(false);

    // 결과 모름 뒤 재발행 때 Agent가 이미 반영됐는지 찾아볼 수 있는지 (#280). GitHub는 이슈 검색과 댓글 표식으로 재대조한다.
    // Notion, Slack은 찾아볼 도구가 없어 Agent가 재발행을 막으므로, 담당자가 확인한 결과를 남겨야 풀린다.
    // 대상별 차이는 여기에만 둔다. 화면은 이 값으로 정한 PublishResolution만 본다.
    private final boolean recheckable;

    PublishTarget(boolean recheckable) {
        this.recheckable = recheckable;
    }

    public boolean recheckable() {
        return recheckable;
    }

    // 값이 없거나 모르는 값이면 GitHub로 둔다 (분류 전 Agent 결과, 모의 실행).
    public static PublishTarget from(JsonNode content) {
        String value = content == null ? "" : content.path("publish_target").asText("");
        for (PublishTarget target : values())
            if (target.name().equals(value)) return target;
        return GITHUB;
    }
}
