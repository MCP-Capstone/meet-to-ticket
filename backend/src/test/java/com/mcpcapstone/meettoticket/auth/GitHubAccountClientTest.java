package com.mcpcapstone.meettoticket.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubAccountClientTest {

	private static final String BASE_URL = "https://api.github.test";

	private MockRestServiceServer server;

	private GitHubAccountClient client() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		return new GitHubAccountClient(builder, BASE_URL);
	}

	@Test
	void 사용자_계정이면_GitHub_기준_login을_돌려준다() {
		GitHubAccountClient client = client();
		server.expect(requestTo(BASE_URL + "/users/octocat"))
				.andRespond(withSuccess("{\"login\":\"Octocat\",\"type\":\"User\"}", MediaType.APPLICATION_JSON));

		assertThat(client.findLogin("octocat")).contains("Octocat");
		server.verify();
	}

	@Test
	void 없는_계정이면_빈_값() {
		GitHubAccountClient client = client();
		server.expect(requestTo(BASE_URL + "/users/no-such-user")).andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThat(client.findLogin("no-such-user")).isEmpty();
	}

	@Test
	void 조직_계정이면_빈_값() {
		GitHubAccountClient client = client();
		server.expect(requestTo(BASE_URL + "/users/github"))
				.andRespond(withSuccess("{\"login\":\"github\",\"type\":\"Organization\"}", MediaType.APPLICATION_JSON));

		assertThat(client.findLogin("github")).isEmpty();
	}

	@Test
	void 호출_제한이면_확인_불가_예외() {
		GitHubAccountClient client = client();
		server.expect(requestTo(BASE_URL + "/users/octocat")).andRespond(withStatus(HttpStatus.FORBIDDEN));

		assertThatThrownBy(() -> client.findLogin("octocat"))
				.isInstanceOf(GitHubAccountClient.UnavailableException.class);
	}
}
