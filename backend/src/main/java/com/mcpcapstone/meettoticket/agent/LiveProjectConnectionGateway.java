package com.mcpcapstone.meettoticket.agent;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;

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
            if (body == null || !body.path("notion").isObject() || !body.path("slack").isObject() || !body.path("members").isArray())
                throw new AgentException(true);
            List<MemberConnection> checked = new ArrayList<>();
            for (JsonNode member : body.path("members")) {
                if (!member.hasNonNull("userId")) throw new AgentException(true);
                checked.add(new MemberConnection(member.path("userId").longValue(), member.path("email").asText(),
                        member.path("status").asText(), member.hasNonNull("slackUserId") ? member.path("slackUserId").asText() : null));
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
        return new Connection(status, node.hasNonNull("value") ? node.path("value").asText() : null);
    }
}
