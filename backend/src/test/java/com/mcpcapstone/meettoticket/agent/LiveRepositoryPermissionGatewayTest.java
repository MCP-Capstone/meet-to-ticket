package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

class LiveRepositoryPermissionGatewayTest {
	private final RestClient.Builder builder = RestClient.builder().baseUrl("http://agent:8001");
	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
	private final LiveRepositoryPermissionGateway gateway = new LiveRepositoryPermissionGateway(builder.build());

	@Test
	void check_members를_호출하고_멤버별_권한을_돌려준다() {
		server.expect(requestTo("http://agent:8001/projects/check-members"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(jsonPath("$.githubOwner").value("owner"))
				.andExpect(jsonPath("$.githubRepo").value("repo"))
				.andExpect(jsonPath("$.githubNames[1]").value("bob"))
				.andRespond(withSuccess(
						"{\"members\":[{\"githubName\":\"alice\",\"permission\":\"GRANTED\"},{\"githubName\":\"bob\",\"permission\":\"ERROR\"}]}",
						MediaType.APPLICATION_JSON));

		assertThat(gateway.check("owner", "repo", List.of("alice", "bob")))
				.containsExactly(
						new RepositoryPermissionGateway.MemberPermission("alice", "GRANTED"),
						new RepositoryPermissionGateway.MemberPermission("bob", "ERROR"));
		server.verify();
	}

	@Test
	void 오류_응답이나_형식_오류는_AgentException이다() {
		server.expect(requestTo("http://agent:8001/projects/check-members"))
				.andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("{\"code\":\"MCP_UNAVAILABLE\"}"));
		assertThatThrownBy(() -> gateway.check("owner", "repo", List.of("alice"))).isInstanceOf(AgentException.class);

		server.reset();
		server.expect(requestTo("http://agent:8001/projects/check-members"))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> gateway.check("owner", "repo", List.of("alice"))).isInstanceOf(AgentException.class);
	}
}
