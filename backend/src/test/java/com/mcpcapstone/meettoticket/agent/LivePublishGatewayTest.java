package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LivePublishGatewayTest {
    @Test
    void 콜백_실패_후_GET으로_완료_결과만_복구한다() {
        var builder = RestClient.builder().baseUrl("http://agent:8001");
        var server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new LivePublishGateway(builder.build());
        server.expect(requestTo("http://agent:8001/publish/batch"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"batchId\":\"batch\",\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://agent:8001/publish/batch"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"batchId\":\"batch\",\"status\":\"SUCCEEDED\",\"items\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://agent:8001/publish/missing"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(gateway.status("batch")).isEmpty();
        assertThat(gateway.status("batch")).get().extracting(PublishGateway.Result::status).isEqualTo("SUCCEEDED");
        // Agent가 모르는 묶음(재시작으로 기록을 잃음)은 "아직 결과 없음"과 구분한다 (#302)
        assertThatThrownBy(() -> gateway.status("missing")).isInstanceOf(PublishBatchLostException.class);
        server.verify();
    }
}
