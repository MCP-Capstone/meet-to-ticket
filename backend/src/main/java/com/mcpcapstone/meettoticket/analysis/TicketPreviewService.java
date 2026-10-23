package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentProperties;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.agent.TicketPreviewGateway;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.project.ProjectService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class TicketPreviewService {
	private final TaskItemRepository items;
	private final PublicationTicketMapper mapper;
	private final TicketPreviewGateway gateway;
	private final AgentProperties properties;
	private final ProjectService projects;
	private final JsonCodec json;
	private final TransactionTemplate tx;

	public Result preview(UUID id, Long userId) {
		// HTTP 대기 중 DB 트랜잭션을 유지하지 않도록 먼저 저장값을 스냅샷으로 만든다.
		Snapshot snapshot = tx.execute(status -> snapshot(id, userId));
		TicketPreviewGateway.View publication = null;
		if (snapshot.target() == PublishTarget.GITHUB) {
			try { publication = gateway.render(snapshot.request()); }
			catch (AgentException e) { throw new BusinessException(ErrorCode.TICKET_PREVIEW_UNAVAILABLE); }
		}
		return new Result(id, snapshot.request().title(), snapshot.status(), snapshot.target(),
				properties.getMode().equalsIgnoreCase("mock") ? "MOCK" : "LIVE", publication, snapshot.backlog());
	}
	private Snapshot snapshot(UUID id, Long userId) {
		TaskItem item = items.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.TICKET_NOT_FOUND));
		var assignee = item.getAssignee();
		if (assignee == null || assignee.getUser() == null || !assignee.getUser().getId().equals(userId))
			throw new BusinessException(ErrorCode.TICKET_ASSIGNEE_REQUIRED);
		projects.requireAccess(item.getTask().getMeeting().getProject().getId(), userId);
		JsonNode c = json.read(item.getContentJson());
		return new Snapshot(mapper.toTicket(item), item.getTicketStatus(), item.getPublishTarget(),
				new Backlog(c.path("description").asText(null), strings(c.path("tasks")),
						strings(c.path("acceptance_criteria")), strings(c.path("evidence_quotes")), strings(c.path("open_questions"))));
	}
	private static List<String> strings(JsonNode node) {
		List<String> out = new ArrayList<>();
		node.forEach(v -> { if (v.isString() && !v.asText().isBlank()) out.add(v.asText()); });
		return out;
	}
	private record Snapshot(PublishGateway.Ticket request, TicketStatus status, PublishTarget target, Backlog backlog) {}
	public record Backlog(String description, List<String> tasks, List<String> acceptanceCriteria,
						  List<String> evidenceQuotes, List<String> openQuestions) {}
	public record Result(UUID ticketId, String title, TicketStatus status, PublishTarget publishTarget,
						 String executionMode, TicketPreviewGateway.View publication, Backlog backlog) {}
}
