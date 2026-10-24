package com.mcpcapstone.meettoticket.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.*;

import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.Set;

public class LivePublishGateway implements PublishGateway {
    private static final Logger log = LoggerFactory.getLogger(LivePublishGateway.class);

    private final RestClient client;

    public LivePublishGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public String mode() {
        return "LIVE";
    }

	@Override
	public Optional<Result> status(String batchId) {
		try {
			Result result = client.get().uri("/publish/{id}", batchId).retrieve().body(Result.class);
			if (result == null || result.status() == null || !batchId.equals(result.batchId())
					|| !Set.of("SUCCEEDED", "PARTIAL_FAILED", "FAILED").contains(result.status()))
				return Optional.empty();
			return Optional.of(result);
		} catch (RestClientResponseException e) {
			if (e.getStatusCode().value() == 404) throw new PublishBatchLostException(batchId);
			throw new AgentException(true);
		} catch (RestClientException e) {
			throw new AgentException(true);
		}
	}

    @Override
    public Optional<Result> submit(Request request) {
        try {
            var response =
                    client.post()
                            .uri("/publish")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (response.getStatusCode().value() != 202
                    || body == null
                    || !request.batchId().equals(body.path("batchId").asText()))
                throw new AgentException(true);
            return Optional.empty();
        } catch (RestClientResponseException e) {
            // 같은 batchId가 이미 처리 중이면 접수된 것이므로 결과를 기다린다.
            if (e.getStatusCode().value() == 409) return Optional.empty();
            log.warn("일괄 발행 접수 실패: status={}, body={}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException e) {
            // 연결 자체가 거부되면 Agent가 받지 않은 것이 확실하다. 읽기 시간 초과는 받았을 수도 있다.
            throw new AgentException(!(e.getCause() instanceof java.net.ConnectException));
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }
}
