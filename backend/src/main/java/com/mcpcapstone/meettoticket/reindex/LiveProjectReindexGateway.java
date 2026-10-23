package com.mcpcapstone.meettoticket.reindex;

import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "live", matchIfMissing = true)
public class LiveProjectReindexGateway implements ProjectReindexGateway {
    private final RestClient client;

    public LiveProjectReindexGateway(AgentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank())
            builder.defaultHeader("X-Internal-Api-Key", properties.getApiKey());
        this.client = builder.build();
    }

    @Override
    public String start(String githubOwner, String githubRepo) {
        try {
            JsonNode body = client.post().uri("/projects/reindex")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("githubOwner", githubOwner, "githubRepo", githubRepo))
                    .retrieve().body(JsonNode.class);
            String jobId = body == null ? null : body.path("jobId").asText(null);
            if (jobId == null || jobId.isBlank()) throw new AgentException(true);
            return jobId;
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }

    @Override
    public AgentStatus status(String jobId) {
        try {
            JsonNode body = client.get().uri("/projects/reindex/{jobId}", jobId).retrieve().body(JsonNode.class);
            if (body == null) throw new AgentException(true);
            ReindexStatus status = ReindexStatus.valueOf(body.path("status").asText("FAILED"));
            JsonNode result = body.path("result");
            return new AgentStatus(
                    status,
                    body.path("errorMessage").asText(null),
                    body.path("progress").path("message").asText(null),
                    nullableInt(result, "indexed"), nullableInt(result, "skipped"), nullableInt(result, "total"),
                    nullableInt(result, "indexCount"), parseInstant(result.path("lastSyncedAt").asText(null)));
        } catch (HttpClientErrorException.NotFound e) {
            throw new JobNotFoundException(jobId);
        } catch (IllegalArgumentException | RestClientException e) {
            throw new AgentException(true);
        }
    }

    @Override
    public void dropIndex(String githubOwner, String githubRepo) {
        try {
            client.post().uri("/projects/drop-index")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("githubOwner", githubOwner, "githubRepo", githubRepo))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }

    private static Integer nullableInt(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asInt() : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
