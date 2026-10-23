package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class TicketPreviewGatewayTest {
	@Test
	void 실제_HTTP_계약으로_본문을_전달하고_비정상_응답을_거절한다() throws Exception {
		var received = new AtomicReference<String>();
		var response = new AtomicReference<String>("{\"kind\":\"CREATE\",\"body\":\"## 최종 본문\",\"secondaryBody\":null,\"labels\":[],\"githubAssignees\":[],\"targetIssueNumber\":null}");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/publication-preview", exchange -> {
			assertThat(exchange.getRequestMethod()).isEqualTo("POST");
			received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			AgentProperties properties = new AgentProperties();
			properties.setMode("live");
			properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
			TicketPreviewGateway gateway = new TicketPreviewGateway(properties);
			var request = new PublishGateway.Ticket("id", new PublishGateway.Project(1L, "프로젝트", "o", "r"),
					2L, "회의", LocalDate.of(2026, 10, 23), "제목", new JsonMapper().readTree("{\"description\":\"저장 본문\"}"), "CREATE", null, false, false, "CONFIRMED");
			assertThat(gateway.render(request).body()).isEqualTo("## 최종 본문");
			var sent = new JsonMapper().readTree(received.get());
			assertThat(sent.path("contentJson").path("description").asText()).isEqualTo("저장 본문");
			assertThat(sent.path("meetingDate").asText()).isEqualTo("2026-10-23");
			response.set("{\"kind\":\"CREATE\",\"body\":\"invalid missing lists\"}");
			assertThatThrownBy(() -> gateway.render(request)).isInstanceOf(AgentException.class);
		} finally { server.stop(0); }
	}
}
