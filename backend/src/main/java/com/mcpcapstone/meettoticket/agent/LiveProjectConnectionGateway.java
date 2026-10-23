package com.mcpcapstone.meettoticket.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LiveProjectConnectionGateway implements ProjectConnectionGateway {
    private static final Logger log = LoggerFactory.getLogger(LiveProjectConnectionGateway.class);

    private final RestClient client;

    public LiveProjectConnectionGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public ConnectionCheck check(String notionPageUrl, String slackChannelId, List<SlackMember> members) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("notionPageUrl", notionPageUrl);
        request.put("slackChannelId", slackChannelId);
        request.put("members", members.stream().map(member -> Map.of("userId", member.userId(), "email", member.email())).toList());
        try {
            JsonNode body = client.post()
                    .uri("/projects/check-connections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null || !body.path("notion").isObject() || !body.path("slack").isObject()
                    || !body.path("members").isArray()) throw new AgentException(true);
            List<MemberConnection> checked = new ArrayList<>();
            for (JsonNode member : body.path("members")) {
                if (!member.hasNonNull("userId")) throw new AgentException(true);
                checked.add(new MemberConnection(
                        member.path("userId").longValue(),
                        member.path("email").asText(),
                        member.path("status").asText(),
                        nullableText(member, "slackUserId")));
            }
            return new ConnectionCheck(readConnection(body.path("notion")), readConnection(body.path("slack")), checked);
        } catch (RestClientResponseException e) {
            log.warn("프로젝트 Notion·Slack 연결 확인 실패: status={}, body={}",
                    e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (RestClientException e) {
            log.warn("프로젝트 Notion·Slack 연결 확인 실패: {}", e.getMessage());
            throw new AgentException(true);
        }
    }

    private static Connection readConnection(JsonNode node) {
        String status = node.path("status").asText();
        if (status.isBlank()) throw new AgentException(true);
        return new Connection(status, nullableText(node, "value"));
    }

    private static String nullableText(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.path(name).asText() : null;
    }
}
