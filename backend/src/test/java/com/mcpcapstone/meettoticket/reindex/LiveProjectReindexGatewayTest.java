package com.mcpcapstone.meettoticket.reindex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LiveProjectReindexGatewayTest {
	@Test
	void 색인_삭제를_Agent_계약대로_요청하고_오류는_AgentException으로_알린다() throws Exception {
		var received = new AtomicReference<String>();
		var status = new AtomicInteger(200);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/projects/drop-index", exchange -> {
			assertThat(exchange.getRequestMethod()).isEqualTo("POST");
			received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] body = "{\"status\":\"DROPPED\",\"deleted\":true}".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			AgentProperties properties = new AgentProperties();
			properties.setMode("live");
			properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
			LiveProjectReindexGateway gateway = new LiveProjectReindexGateway(properties);

			gateway.dropIndex("MCP-Capstone", "meet-to-ticket");
			var sent = new JsonMapper().readTree(received.get());
			assertThat(sent.path("githubOwner").asText()).isEqualTo("MCP-Capstone");
			assertThat(sent.path("githubRepo").asText()).isEqualTo("meet-to-ticket");

			status.set(502);
			assertThatThrownBy(() -> gateway.dropIndex("o", "r")).isInstanceOf(AgentException.class);
		} finally { server.stop(0); }
	}
}
