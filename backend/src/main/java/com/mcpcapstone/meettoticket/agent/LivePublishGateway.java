package com.mcpcapstone.meettoticket.agent;

import org.springframework.http.MediaType;
import org.springframework.web.client.*;

import tools.jackson.databind.JsonNode;

// Agent /publish는 비동기 202만 반환하고 최종 결과는 /internal/publish-batches/...로 보고한다.
public class LivePublishGateway implements PublishGateway {
    private final RestClient client;

    public LivePublishGateway(RestClient client) {
        this.client = client;
    }

    @Override
    public void submit(AgentContracts.PublishBatchRequest request) {
        try {
            var response = client.post()
                    .uri("/publish")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .toEntity(JsonNode.class);
            JsonNode body = response.getBody();
            if (response.getStatusCode().value() != 202 || body == null
                    || !request.batchId().toString().equals(body.path("batchId").asText())) {
                // 2xx이나 본문 불일치는 Agent가 받았는지 알 수 없어 같은 batchId로 콜백을 기다린다.
                throw new AgentException(true);
            }
        } catch (RestClientResponseException e) {
            // 4xx는 Agent가 요청을 거부한 것이 확실하고, 5xx는 수신 여부를 알 수 없다.
            throw new AgentException(e.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException e) {
            // 연결 거부만 미수신으로 간주한다. 읽기 시간 초과는 Agent가 실행했을 수 있다.
            throw new AgentException(!(e.getCause() instanceof java.net.ConnectException));
        } catch (RestClientException e) {
            throw new AgentException(true);
        }
    }
}
