package com.mcpcapstone.meettoticket.agent;

import org.springframework.http.MediaType;
import org.springframework.web.client.*;

import java.util.UUID;

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
                            .uri("/internal/v1/analysis-tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .toEntity(AgentContracts.Accepted.class);
            if (response.getStatusCode().value() != 202
                    || response.getBody() == null
                    || !request.taskId().equals(response.getBody().taskId()))
                throw new AgentException(true);
            return response.getBody();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409)
                return new AgentContracts.Accepted(
                        request.taskId(),
                        com.mcpcapstone.meettoticket.analysis.TaskStatus.QUEUED,
                        null,
                        null,
                        null);
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }

    @Override
    public AgentContracts.Result status(UUID taskId) {
        try {
            var result =
                    client.get()
                            .uri("/internal/v1/analysis-tasks/{taskId}", taskId)
                            .retrieve()
                            .body(AgentContracts.Result.class);
            if (result == null) throw new AgentException(true);
            return result;
        } catch (RestClientResponseException e) {
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }
}
