package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.*;

class LiveAgentGatewayTest {
    @Test
    void 내부인증헤더와_경로로_한번접수하고_GET으로만조회한다() {
        UUID id = UUID.randomUUID();
        RestClient.Builder builder =
                RestClient.builder()
                        .baseUrl("http://agent:8001")
                        .defaultHeader("X-Internal-Api-Key", "test-internal-key");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LiveAgentGateway gateway = new LiveAgentGateway(builder.build());
        server.expect(requestTo("http://agent:8001/internal/v1/analysis-tasks"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", "test-internal-key"))
                .andExpect(jsonPath("$.taskId").value(id.toString()))
                .andRespond(
                        withStatus(HttpStatus.ACCEPTED)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body("{\"taskId\":\"" + id + "\",\"status\":\"QUEUED\"}"));
        var request =
                new AgentContracts.StartRequest(
                        id,
                        new AgentContracts.MeetingData(
                                1L,
                                "회의",
                                LocalDate.of(2026, 10, 22),
                                "Asia/Seoul",
                                List.of(),
                                "a.txt",
                                "본문"),
                        new AgentContracts.ProjectData(1L, "시연", "owner", "repo"),
                        List.of());
        server.expect(requestTo("http://agent:8001/internal/v1/analysis-tasks/" + id))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                "{\"taskId\":\""
                                        + id
                                        + "\",\"meetingId\":1,\"status\":\"RUNNING\",\"stage\":\"REVIEWING\",\"items\":[]}",
                                MediaType.APPLICATION_JSON));
        assertThat(gateway.submit(request).taskId()).isEqualTo(id);

        assertThat(gateway.status(id).stage().name()).isEqualTo("REVIEWING");
        server.verify();
    }
}
