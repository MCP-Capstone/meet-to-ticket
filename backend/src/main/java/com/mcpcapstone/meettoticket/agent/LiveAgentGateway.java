package com.mcpcapstone.meettoticket.agent;

import org.springframework.http.MediaType;
import org.springframework.web.client.*;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

// Agent(#52) API: POST /runs (202 접수), GET /runs/{taskId} (진행 / 최종 결과)
// Agent 콜백(/internal/tasks/...)은 쓰지 않고 Spring이 조회한다.
public class LiveAgentGateway implements AgentGateway {
    private final RestClient client;

    public LiveAgentGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public String mode() {
        return "LIVE";
    }

    @Override
    public AgentContracts.Accepted submit(AgentContracts.StartRequest request) {
        try {
            var response =
                    client.post()
                            .uri("/runs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (response.getStatusCode().value() != 202
                    || body == null
                    || !request.taskId().toString().equals(body.path("taskId").asText()))
                throw new AgentException(true);
            return queued(request.taskId());
        } catch (RestClientResponseException e) {
            // 같은 taskId가 이미 처리 중이면 재접수하지 않고 조회만 한다.
            if (e.getStatusCode().value() == 409) return queued(request.taskId());
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException e) {
            // 연결 자체가 거부되면 Agent가 받지 않은 것이 확실하다. 읽기 시간 초과는 받았을 수도 있다.
            throw new AgentException(!(e.getCause() instanceof java.net.ConnectException));
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }

    @Override
    public AgentContracts.Result status(UUID taskId) {
        try {
            JsonNode run = client.get().uri("/runs/{taskId}", taskId).retrieve().body(JsonNode.class);
            if (run == null) throw new AgentException(true);
            return AgentRunMapper.toResult(taskId, run);
        } catch (RestClientResponseException e) {
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }

    private static AgentContracts.Accepted queued(UUID taskId) {
        return new AgentContracts.Accepted(
                taskId, com.mcpcapstone.meettoticket.analysis.TaskStatus.QUEUED, null, null, null);
    }
}
