package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LiveProjectConnectionGatewayTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://agent:8001");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final LiveProjectConnectionGateway gateway = new LiveProjectConnectionGateway(builder.build());

    @Test
    void 프로젝트_연결과_멤버_Slack_ID_확인을_호출한다() {
        server.expect(requestTo("http://agent:8001/projects/check-connections"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.notionPageUrl").value("https://www.notion.so/plan"))
                .andExpect(jsonPath("$.slackChannelId").value("project-channel"))
                .andExpect(jsonPath("$.members[0].userId").value(7))
                .andExpect(jsonPath("$.members[0].email").value("member@example.com"))
                .andRespond(withSuccess("""
                        {"notion":{"status":"CONNECTED","value":"https://www.notion.so/normalized"},
                         "slack":{"status":"CONNECTED","value":"C0123456789"},
                         "members":[{"userId":7,"email":"member@example.com","status":"CONNECTED","slackUserId":"U0123456789"}]}
                        """, MediaType.APPLICATION_JSON));

        ProjectConnectionGateway.ConnectionCheck result = gateway.check(
                "https://www.notion.so/plan", "project-channel",
                List.of(new ProjectConnectionGateway.SlackMember(7L, "member@example.com")));

        assertThat(result.notion()).isEqualTo(new ProjectConnectionGateway.Connection("CONNECTED", "https://www.notion.so/normalized"));
        assertThat(result.slack()).isEqualTo(new ProjectConnectionGateway.Connection("CONNECTED", "C0123456789"));
        assertThat(result.members()).containsExactly(
                new ProjectConnectionGateway.MemberConnection(7L, "member@example.com", "CONNECTED", "U0123456789"));
        server.verify();
    }
}
