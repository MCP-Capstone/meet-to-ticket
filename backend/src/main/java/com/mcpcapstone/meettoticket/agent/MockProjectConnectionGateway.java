package com.mcpcapstone.meettoticket.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Agent 없이 프로젝트 저장 흐름을 확인하는 모의 구현.
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockProjectConnectionGateway implements ProjectConnectionGateway {
    @Override
    public ConnectionCheck check(String notionPageUrl, String slackChannelId) {
        return new ConnectionCheck(connection(notionPageUrl), connection(slackChannelId));
    }

    private static Connection connection(String input) {
        if (input == null) return new Connection("NOT_CONNECTED", null);
        String lower = input.toLowerCase();
        return lower.contains("missing") || lower.contains("error") || lower.contains("denied")
                ? new Connection("ERROR", null)
                : new Connection("CONNECTED", input);
    }
}
