package com.mcpcapstone.meettoticket.auth;

import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

// 가입 시 GitHub 계정 존재 확인 (GET /users/{login}). 공개 정보라 토큰 없이 호출한다.
// 토큰이 없으면 IP당 시간당 60회 제한이 있어, 확인하지 못한 경우는 호출한 쪽이 처리한다.
@Component
public class GitHubAccountClient {

	private final RestClient client;

	@Autowired
	public GitHubAccountClient(@Value("${app.github.api-base-url:https://api.github.com}") String baseUrl,
			@Value("${app.github.timeout:3s}") Duration timeout) {
		this(RestClient.builder().requestFactory(requestFactory(timeout)), baseUrl);
	}

	// 테스트에서 MockRestServiceServer를 붙인 builder를 넘긴다.
	GitHubAccountClient(RestClient.Builder builder, String baseUrl) {
		this.client = builder.baseUrl(baseUrl)
				.defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
				.defaultHeader("X-GitHub-Api-Version", "2022-11-28")
				.build();
	}

	private static SimpleClientHttpRequestFactory requestFactory(Duration timeout) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(timeout);
		factory.setReadTimeout(timeout);
		return factory;
	}

	// 계정이 있으면 GitHub 기준 login 값을, 없으면 빈 값을 돌려준다. 확인하지 못하면 UnavailableException.
	// 사용자 계정만 받는다. 조직(Organization)은 이슈 담당자로 지정할 수 없다.
	public Optional<String> findLogin(String login) {
		try {
			JsonNode user = client.get().uri("/users/{login}", login).retrieve().body(JsonNode.class);
			if (user == null || !"User".equals(user.path("type").asText())) {
				return Optional.empty();
			}
			return Optional.of(user.path("login").asText(login));
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
				return Optional.empty();
			}
			// 403/429(호출 제한), 5xx는 계정 유무를 알 수 없는 경우다.
			throw new UnavailableException("HTTP " + e.getStatusCode().value());
		} catch (RestClientException e) {
			throw new UnavailableException(e.getClass().getSimpleName());
		}
	}

	public static class UnavailableException extends RuntimeException {
		UnavailableException(String reason) {
			super(reason);
		}
	}
}
