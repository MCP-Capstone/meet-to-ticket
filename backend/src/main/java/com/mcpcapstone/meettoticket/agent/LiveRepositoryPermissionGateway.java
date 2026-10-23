package com.mcpcapstone.meettoticket.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;

import tools.jackson.databind.JsonNode;

import java.util.*;

public class LiveRepositoryPermissionGateway implements RepositoryPermissionGateway {
    private static final Logger log = LoggerFactory.getLogger(LiveRepositoryPermissionGateway.class);

    private final RestClient client;

    public LiveRepositoryPermissionGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public List<MemberPermission> check(String githubOwner, String githubRepo, List<String> githubNames) {
        try {
            JsonNode body =
                    client.post()
                            .uri("/projects/check-members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(Map.of(
                                    "githubOwner", githubOwner,
                                    "githubRepo", githubRepo,
                                    "githubNames", githubNames))
                            .retrieve()
                            .body(JsonNode.class);
            if (body == null || !body.path("members").isArray()) throw new AgentException(true);
            List<MemberPermission> result = new ArrayList<>();
            for (JsonNode m : body.path("members"))
                result.add(new MemberPermission(m.path("githubName").asText(), m.path("permission").asText()));
            return result;
        } catch (RestClientResponseException e) {
            // MCP 전체 오류 코드는 Agent가 응답 본문에 그대로 담아 보낸다. 원인 구분을 위해 남긴다.
            log.warn("멤버 저장소 권한 확인 실패: status={}, body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (RestClientException e) {
            log.warn("멤버 저장소 권한 확인 실패: {}", e.getMessage());
            throw new AgentException(true);
        }
    }
}
