package com.mcpcapstone.meettoticket.agent;

import java.util.List;

// Agent POST /projects/check-connections. 프로젝트 저장 후 Notion·Slack 연결과 멤버 Slack ID를 확인한다.
// 외부 확인 실패는 응답의 ERROR로 표현하며, 프로젝트 저장 자체를 되돌리지 않는다.
public interface ProjectConnectionGateway {
    ConnectionCheck check(String notionPageUrl, String slackChannelId, List<SlackMember> members);

    record SlackMember(Long userId, String email) {}

    record Connection(String status, String value) {}

    record MemberConnection(Long userId, String email, String status, String slackUserId) {}

    record ConnectionCheck(Connection notion, Connection slack, List<MemberConnection> members) {
        public static ConnectionCheck failed(List<SlackMember> targets) {
            return new ConnectionCheck(
                    new Connection("ERROR", null),
                    new Connection("ERROR", null),
                    targets.stream().map(target -> new MemberConnection(target.userId(), target.email(), "ERROR", null)).toList());
        }
    }
}
