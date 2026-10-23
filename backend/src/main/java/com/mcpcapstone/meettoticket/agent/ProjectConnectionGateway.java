package com.mcpcapstone.meettoticket.agent;

// Agent POST /projects/check-connections. 프로젝트 저장 후 Notion·Slack 연결을 확인한다.
// 외부 확인 실패는 ERROR 상태로 표현하며, 프로젝트 저장 자체를 되돌리지 않는다.
public interface ProjectConnectionGateway {
    ConnectionCheck check(String notionPageUrl, String slackChannelId);

    record Connection(String status, String value) {}

    record ConnectionCheck(Connection notion, Connection slack) {
        public static ConnectionCheck failed() {
            return new ConnectionCheck(new Connection("ERROR", null), new Connection("ERROR", null));
        }
    }
}
