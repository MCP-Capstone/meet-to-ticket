package com.mcpcapstone.meettoticket.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Agent 없이 프로젝트 저장 화면을 검증하는 모의 구현. missing/error가 든 값은 연결 실패로 재현한다.
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockProjectConnectionGateway implements ProjectConnectionGateway {
    @Override
    public ConnectionCheck check(String notionPageUrl, String slackChannelId, List<SlackMember> members) {
        return new ConnectionCheck(
                connection(notionPageUrl, notionPageUrl),
                connection(slackChannelId, slackChannelId),
                members.stream().map(member -> member(member)).toList());
    }

    private static Connection connection(String input, String value) {
        if (input == null) return new Connection("NOT_CONNECTED", null);
        return new Connection(fails(input) ? "ERROR" : "CONNECTED", fails(input) ? null : value);
    }

    private static MemberConnection member(SlackMember member) {
        if (fails(member.email())) return new MemberConnection(member.userId(), member.email(), "NOT_CONNECTED", null);
        return new MemberConnection(member.userId(), member.email(), "CONNECTED", "U" + member.userId());
    }

    private static boolean fails(String value) {
        String lower = value == null ? "" : value.toLowerCase();
        return lower.contains("missing") || lower.contains("error") || lower.contains("denied");
    }
}
