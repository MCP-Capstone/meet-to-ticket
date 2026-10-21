package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.mcpcapstone.meettoticket.analysis.TaskStatus;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

class LiveAgentGatewayTest {
	@Test
	void Agent의_runs_경로로_한번_접수하고_GET으로_조회한다() {
		UUID id = UUID.randomUUID();
		RestClient.Builder builder = RestClient.builder().baseUrl("http://agent:8001");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		LiveAgentGateway gateway = new LiveAgentGateway(builder.build());
		server.expect(requestTo("http://agent:8001/runs"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.taskId").value(id.toString()))
				.andExpect(jsonPath("$.meeting.originalContent").value("본문"))
				.andExpect(jsonPath("$.project.githubRepo").value("repo"))
				.andRespond(withStatus(HttpStatus.ACCEPTED)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"taskId\":\"" + id + "\",\"status\":\"ACCEPTED\"}"));
		server.expect(requestTo("http://agent:8001/runs/" + id))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("{\"taskId\":\"" + id + "\",\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
		var request = new AgentContracts.StartRequest(
				id,
				new AgentContracts.MeetingData(1L, "회의", LocalDate.of(2026, 10, 22), "Asia/Seoul", List.of(), "a.txt", "본문"),
				new AgentContracts.ProjectData(1L, "시연", "owner", "repo"),
				List.of());

		assertThat(gateway.submit(request).status()).isEqualTo(TaskStatus.QUEUED);
		assertThat(gateway.status(id).status()).isEqualTo(TaskStatus.RUNNING);
		server.verify();
	}

	@Test
	void 이미_처리_중인_작업이면_재접수하지_않고_조회로_넘어간다() {
		UUID id = UUID.randomUUID();
		RestClient.Builder builder = RestClient.builder().baseUrl("http://agent:8001");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("http://agent:8001/runs")).andRespond(withStatus(HttpStatus.CONFLICT));
		var request = new AgentContracts.StartRequest(
				id,
				new AgentContracts.MeetingData(1L, "회의", LocalDate.of(2026, 10, 22), "Asia/Seoul", List.of(), "a.txt", "본문"),
				new AgentContracts.ProjectData(1L, "시연", "owner", "repo"),
				List.of());

		assertThat(new LiveAgentGateway(builder.build()).submit(request).taskId()).isEqualTo(id);
	}
}
