package com.mcpcapstone.meettoticket.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;

@Component
public class TicketPreviewGateway {
	private final RestClient client;
	private final boolean mock;

	public TicketPreviewGateway(AgentProperties properties) {
		mock = "mock".equalsIgnoreCase(properties.getMode());
		var factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(properties.getConnectTimeout());
		factory.setReadTimeout(properties.getReadTimeout());
		var builder = RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory);
		if (properties.getApiKey() != null && !properties.getApiKey().isBlank())
			builder.defaultHeader("X-Internal-Api-Key", properties.getApiKey());
		client = builder.build();
	}

	public View render(PublishGateway.Ticket ticket) {
		if (mock) return mockView(ticket);
		try {
			View view = client.post().uri("/publication-preview").contentType(MediaType.APPLICATION_JSON)
					.body(ticket).retrieve().body(View.class);
			if (view == null || view.kind() == null || !Set.of("CREATE", "LINK", "UPDATE").contains(view.kind())
					|| view.body() == null || view.labels() == null || view.githubAssignees() == null) throw new AgentException(true);
			return view;
		} catch (RestClientException e) {
			throw new AgentException(true);
		}
	}

	private View mockView(PublishGateway.Ticket t) {
		JsonNode c = t.contentJson();
		StringBuilder body = new StringBuilder("## 작업 내용\n" + c.path("description").asText("") + "\n");
		append(body, "상세 작업", strings(c.path("tasks")), "- [ ] ");
		append(body, "완료 조건", strings(c.path("acceptance_criteria")), "- ");
		append(body, "근거", strings(c.path("evidence_quotes")), "> ");
		append(body, "담당", strings(c.path("assignees").path("names")), "- ");
		var due = c.path("due");
		body.append("\n## 기한\n").append(due.path("date").asText(due.path("text").asText("없음")))
				.append(" ").append(due.path("time").asText(""));
		append(body, "확인 필요", strings(c.path("open_questions")), "- ");
		return new View(t.processingType(), body.toString(), null, strings(c.path("labels")), strings(c.path("assignees").path("github")), t.targetIssueNumber());
	}
	private static void append(StringBuilder out, String heading, List<String> values, String prefix) {
		if (values.isEmpty()) return;
		out.append("\n## ").append(heading).append("\n");
		values.forEach(v -> out.append(prefix).append(v).append("\n"));
	}
	private static List<String> strings(JsonNode values) {
		List<String> out = new ArrayList<>();
		values.forEach(v -> { if (v.isString() && !v.asText().isBlank()) out.add(v.asText()); });
		return out;
	}
	public record View(String kind, String body, String secondaryBody, List<String> labels,
					   List<String> githubAssignees, Integer targetIssueNumber) {}
}
