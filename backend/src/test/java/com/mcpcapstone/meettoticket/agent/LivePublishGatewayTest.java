package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LivePublishGatewayTest {
    @Test
    void Agent_publish에_같은_batchId와_확인된_티켓만_접수한다() {
        UUID batchId = UUID.randomUUID();
        UUID ticketId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder().baseUrl("http://agent:8001");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://agent:8001/publish"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.batchId").value(batchId.toString()))
                .andExpect(jsonPath("$.tickets[0].ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.tickets[0].reviewStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.tickets[0].project.slackChannelId").value("C123"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"batchId\":\"" + batchId + "\",\"status\":\"ACCEPTED\"}"));

        var request = new AgentContracts.PublishBatchRequest(batchId, List.of(new AgentContracts.PublishTicket(
                ticketId,
                new AgentContracts.PublishProject(1L, "프로젝트", "owner", "repo", "https://notion.so/plan", "C123"),
                2L, "회의", null, "제목", new tools.jackson.databind.json.JsonMapper().createObjectNode(),
                "CREATE", null, false, "GITHUB", List.of(), "CONFIRMED")));

        assertThatCode(() -> new LivePublishGateway(builder.build()).submit(request)).doesNotThrowAnyException();
        server.verify();
    }
}
