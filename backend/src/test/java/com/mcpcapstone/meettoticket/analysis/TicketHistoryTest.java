package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import tools.jackson.databind.JsonNode;

// 에이전트 원본/수정본/발행본 리비전과 상태 이력, 승인 이력 (#264).
// 분석 결과가 저장되는 실제 경로(AnalysisStore)로 티켓을 만들고, 카드 수정/확정/발행 API로 이력이 쌓이는지 본다.
@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=history-test-token",
		"spring.datasource.url=jdbc:h2:mem:ticket-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketHistoryTest {
	private static final String TOKEN = "history-test-token";

	@MockitoBean AnalysisTaskPublisher publisher;
	@MockitoBean PublishGateway gateway;
	@Autowired AnalysisTaskListener listener;
	@Autowired AnalysisCoordinator coordinator;
	@Autowired TaskItemRepository items;
	@Autowired TaskItemRevisionRepository revisions;
	@Autowired TicketStatusHistoryRepository statusHistory;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired UserRepository users;
	@Autowired MockMvc mvc;
	@Autowired PlatformTransactionManager txManager;
	@Autowired JsonCodec json;

	private MockHttpSession me;
	private Long projectId;
	private UUID ticket;

	@BeforeEach
	void setUp() throws Exception {
		when(gateway.mode()).thenReturn("LIVE");
		when(gateway.submit(any())).thenReturn(Optional.empty()); // 접수만 하고 결과는 나중에 보고된다
		me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
						.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
				.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
		projectId = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow().getId();
		ticket = analyze("함주헌");
	}

	private UUID analyze(String participants) throws Exception {
		return analyze(projectId, participants);
	}

	private UUID analyze(Long projectId, String participants) throws Exception {
		String text = "# 이력 테스트\n회의 날짜: 2026-10-22\n회의 참여자: " + participants + "\n\n[구간 1] API를 내일 오후 6시까지 수정한다.\n";
		String upload = mvc.perform(multipart("/api/projects/{id}/meetings", projectId)
						.file(new MockMultipartFile("file", "t.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)))
						.session(me))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long meeting = json.read(upload).path("meetingId").longValue();
		String started = mvc.perform(post("/api/meetings/{id}/analysis-tasks", meeting).session(me))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID task = UUID.fromString(json.read(started).path("taskId").asText());
		listener.receive(task.toString());
		coordinator.pollPending();
		coordinator.pollPending();
		return items.findByTaskIdOrderById(task).get(0).getId();
	}

	private MockHttpSession userSession(User user) {
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		return s;
	}

	private User newUser() {
		return users.save(new User("th" + UUID.randomUUID().toString().substring(0, 8), "hash", "검토자"));
	}

	private void act(String action) throws Exception {
		mvc.perform(post("/api/tickets/{id}/{action}", ticket, action).session(me)).andExpect(status().isOk());
	}

	private void edit(String body) throws Exception {
		mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json").content(body))
				.andExpect(status().isOk());
	}

	private JsonNode history() throws Exception {
		return json.read(mvc.perform(get("/api/tickets/{id}/history", ticket).session(me))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

	private List<String> kinds(JsonNode h) {
		List<String> out = new ArrayList<>();
		h.path("revisions").forEach(r -> out.add(r.path("kind").asText()));
		return out;
	}

	private List<String> transitions(JsonNode h) {
		List<String> out = new ArrayList<>();
		h.path("statusHistory").forEach(c -> out.add(c.path("from").asText("null") + ">" + c.path("to").asText()
				+ ":" + c.path("reason").asText("null")));
		return out;
	}

	private UUID publish() throws Exception {
		String body = mvc.perform(post("/api/tickets/publish").session(me)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.read(body).path("batchId").asText());
	}

	private void report(UUID batch, String itemJson) throws Exception {
		mvc.perform(post("/internal/publish-batches/{id}/result", batch).header("X-Internal-Token", TOKEN)
						.contentType("application/json")
						.content("{\"batchId\":\"%s\",\"status\":\"SUCCEEDED\",\"items\":[%s]}".formatted(batch, itemJson)))
				.andExpect(status().isAccepted());
	}

	@Test
	void 분석_결과로_티켓이_만들어지면_AGENT_리비전_1건과_상태_이력의_시작이_남는다() throws Exception {
		JsonNode h = history();

		assertThat(kinds(h)).containsExactly("AGENT");
		JsonNode agent = h.path("revisions").get(0);
		assertThat(agent.path("rev").asInt()).isEqualTo(1);
		assertThat(agent.path("schemaVersion").asInt()).isEqualTo(1);
		assertThat(agent.path("actor").isNull()).isTrue();
		var item = items.findById(ticket).orElseThrow();
		assertThat(agent.path("snapshot").path("title").asText()).isEqualTo(item.getTitle());
		assertThat(agent.path("snapshot").path("assignee_name").asText()).isEqualTo("함주헌");
		assertThat(agent.path("snapshot").path("publish_target").asText()).isEqualTo("GITHUB");
		assertThat(agent.path("snapshot").path("content").isObject()).isTrue();
		assertThat(transitions(h)).containsExactly("null>PENDING_REVIEW:AGENT_RESULT");
		assertThat(h.path("approval").isNull()).isTrue();
	}

	@Test
	void 카드를_여러_번_고쳐도_확정할_때_최종_편집본_1건만_USER_EDIT로_쌓는다() throws Exception {
		// 모의 Agent 결과에는 본문(description)이 없다. 수정으로 생긴 값이 원본에 섞이지 않아야 한다.
		assertThat(history().path("revisions").get(0).path("snapshot").path("content").has("description")).isFalse();
		edit("{\"title\":\"  고친 제목  \"}");
		edit("{\"description\":\"첫 번째 본문\"}");
		edit("{\"description\":\"최종 본문\",\"due\":{\"text\":\"금요일까지\",\"date\":\"2026-10-30\",\"time\":\"17:30\"}}");

		// 수정 호출마다 쌓지 않는다
		assertThat(kinds(history())).containsExactly("AGENT");

		act("confirm");

		JsonNode h = history();
		assertThat(kinds(h)).containsExactly("AGENT", "USER_EDIT");
		JsonNode edited = h.path("revisions").get(1);
		assertThat(edited.path("rev").asInt()).isEqualTo(2);
		assertThat(edited.path("actor").path("name").asText()).isEqualTo("함주헌");
		JsonNode snap = edited.path("snapshot");
		assertThat(snap.path("title").asText()).isEqualTo("고친 제목");
		assertThat(snap.path("content").path("description").asText()).isEqualTo("최종 본문");
		assertThat(snap.path("content").path("due").path("date").asText()).isEqualTo("2026-10-30");
		// 에이전트 원본은 수정 뒤에도 그대로 남는다
		assertThat(h.path("revisions").get(0).path("snapshot").path("content").has("description")).isFalse();
		assertThat(h.path("revisions").get(0).path("snapshot").path("title").asText()).isNotEqualTo("고친 제목");
	}

	@Test
	void 고친_것이_없으면_확정해도_USER_EDIT를_쌓지_않는다() throws Exception {
		act("confirm");
		assertThat(kinds(history())).containsExactly("AGENT");
		// 같은 값으로 다시 써도 달라진 것이 아니다
		act("reopen");
		edit(json.write(java.util.Map.of("title", items.findById(ticket).orElseThrow().getTitle())));
		act("confirm");
		assertThat(kinds(history())).containsExactly("AGENT");
	}

	@Test
	void 다시_열어_또_고치고_확정하면_바뀐_확정마다_한_건씩_쌓는다() throws Exception {
		edit("{\"description\":\"1차 수정\"}");
		act("confirm");
		act("reopen");
		act("confirm"); // 그대로 다시 확정: 쌓지 않는다
		act("reopen");
		edit("{\"description\":\"2차 수정\"}");
		act("confirm");

		JsonNode h = history();
		assertThat(kinds(h)).containsExactly("AGENT", "USER_EDIT", "USER_EDIT");
		assertThat(h.path("revisions").get(2).path("snapshot").path("content").path("description").asText()).isEqualTo("2차 수정");
	}

	@Test
	void 거절하면_수정본을_쌓지_않는다() throws Exception {
		edit("{\"description\":\"거절할 티켓\"}");
		act("reject");
		assertThat(kinds(history())).containsExactly("AGENT");
	}

	@Test
	void 모든_상태_전이에_이력이_남고_승인한_사람과_시각이_조회된다() throws Exception {
		act("confirm");
		act("reopen");
		act("reject");
		act("restore");
		act("confirm");

		JsonNode h = history();
		assertThat(transitions(h)).containsExactly(
				"null>PENDING_REVIEW:AGENT_RESULT",
				"PENDING_REVIEW>CONFIRMED:CONFIRM",
				"CONFIRMED>PENDING_REVIEW:REOPEN",
				"PENDING_REVIEW>REJECTED:REJECT",
				"REJECTED>PENDING_REVIEW:RESTORE",
				"PENDING_REVIEW>CONFIRMED:CONFIRM");
		JsonNode confirmedByUser = h.path("statusHistory").get(1);
		assertThat(confirmedByUser.path("actor").path("name").asText()).isEqualTo("함주헌");
		assertThat(confirmedByUser.path("createdAt").asText()).isNotBlank();
		// 마지막 승인이 기준이다
		assertThat(h.path("approval").path("actor").path("name").asText()).isEqualTo("함주헌");
		assertThat(h.path("approval").path("approvedAt").asText())
				.isEqualTo(h.path("statusHistory").get(5).path("createdAt").asText());
	}

	@Test
	void 확정한_뒤_다시_열어_거절하면_승인은_없다() throws Exception {
		act("confirm");
		assertThat(history().path("approval").path("actor").path("name").asText()).isEqualTo("함주헌");
		act("reopen");
		assertThat(history().path("approval").isNull()).isTrue();
		act("reject");
		JsonNode h = history();
		assertThat(h.path("approval").isNull()).isTrue();
		// 지난 확정은 상태 이력에 그대로 남는다
		assertThat(transitions(h)).contains("PENDING_REVIEW>CONFIRMED:CONFIRM");
	}

	@Test
	void 발행_실패를_수정하러_되돌리면_다시_확정할_때까지_승인은_없다() throws Exception {
		act("confirm");
		report(publish(), "{\"ticketId\":\"%s\",\"processingType\":\"CREATE\",\"status\":\"FAILED\",\"errorCode\":\"GITHUB_FORBIDDEN\",\"retryable\":false}".formatted(ticket));
		// 실패 상태는 확정을 거쳐 온 상태라 승인이 남아 있다
		assertThat(history().path("approval").path("actor").path("name").asText()).isEqualTo("함주헌");
		act("revise");
		assertThat(history().path("approval").isNull()).isTrue();
		act("confirm");
		assertThat(history().path("approval").path("actor").path("name").asText()).isEqualTo("함주헌");
	}

	@Test
	void 성공으로_끝난_전이의_reason은_알림_댓글_실패여도_PUBLISH_RESULT다() throws Exception {
		var item = items.findById(ticket).orElseThrow();
		item.changePublication(PublishTarget.GITHUB, ProcessingType.UPDATE);
		items.save(item);
		act("confirm");
		report(publish(), "{\"ticketId\":\"%s\",\"processingType\":\"UPDATE\",\"status\":\"UPDATED\",\"githubIssueNumber\":9,\"githubIssueUrl\":\"u9\",\"errorCode\":\"COMMENT_FAILED\",\"errorMessage\":\"댓글 실패\",\"retryable\":true}".formatted(ticket));

		assertThat(items.findById(ticket).orElseThrow().getPublishErrorCode()).isEqualTo("COMMENT_FAILED");
		JsonNode h = history();
		assertThat(transitions(h)).contains("PUBLISHING>UPDATED:PUBLISH_RESULT");
		assertThat(kinds(h)).contains("PUBLISHED");
	}

	@Test
	void 대표_담당을_지정하면_ASSIGN_전이가_업로더_행위로_남는다() throws Exception {
		Long memberId = members.findByProjectIdOrderById(projectId).stream()
				.filter(m -> "함주헌".equals(m.getName())).findFirst().orElseThrow().getId();
		var item = items.findById(ticket).orElseThrow();
		item.assignTo(null); // 담당자를 정하지 못한 티켓
		items.save(item);

		mvc.perform(put("/api/tickets/{id}/assignee", ticket).session(me).contentType("application/json")
				.content("{\"memberId\":" + memberId + "}")).andExpect(status().isOk());

		JsonNode h = history();
		assertThat(transitions(h)).last().isEqualTo("ASSIGNEE_REQUIRED>PENDING_REVIEW:ASSIGN");
		assertThat(h.path("statusHistory").get(h.path("statusHistory").size() - 1).path("actor").path("name").asText()).isEqualTo("함주헌");
	}

	@Test
	void 허용되지_않아_409가_난_전이는_이력을_남기지_않는다() throws Exception {
		mvc.perform(post("/api/tickets/{id}/reopen", ticket).session(me)).andExpect(status().isConflict());
		assertThat(transitions(history())).containsExactly("null>PENDING_REVIEW:AGENT_RESULT");
	}

	@Test
	void 발행에_성공하면_PUBLISHED_리비전을_쌓고_발행_상태_전이를_남긴다() throws Exception {
		edit("{\"description\":\"발행할 본문\"}");
		act("confirm");
		UUID batch = publish();
		report(batch, """
				{"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","githubIssueNumber":42,"githubIssueUrl":"https://github.com/MCP-Capstone/mcp-capstone/issues/42"}"""
				.formatted(ticket));

		JsonNode h = history();
		assertThat(kinds(h)).containsExactly("AGENT", "USER_EDIT", "PUBLISHED");
		JsonNode published = h.path("revisions").get(2);
		assertThat(published.path("rev").asInt()).isEqualTo(3);
		assertThat(published.path("actor").path("name").asText()).isEqualTo("함주헌");
		assertThat(published.path("snapshot").path("content").path("description").asText()).isEqualTo("발행할 본문");
		assertThat(published.path("snapshot").path("result").path("github_issue_number").asInt()).isEqualTo(42);
		assertThat(published.path("snapshot").path("result").path("status").asText()).isEqualTo("PUBLISHED");
		assertThat(transitions(h)).containsExactly(
				"null>PENDING_REVIEW:AGENT_RESULT",
				"PENDING_REVIEW>CONFIRMED:CONFIRM",
				"CONFIRMED>PUBLISHING:PUBLISH_REQUESTED",
				"PUBLISHING>PUBLISHED:PUBLISH_RESULT");
		// 발행 요청은 사용자가, 결과 반영은 시스템이 바꾼 것이다
		assertThat(h.path("statusHistory").get(2).path("actor").path("name").asText()).isEqualTo("함주헌");
		assertThat(h.path("statusHistory").get(3).path("actor").isNull()).isTrue();

		// 같은 결과가 다시 보고돼도 더 쌓이지 않는다
		report(batch, "{\"ticketId\":\"%s\",\"processingType\":\"CREATE\",\"status\":\"PUBLISHED\"}".formatted(ticket));
		assertThat(kinds(history())).containsExactly("AGENT", "USER_EDIT", "PUBLISHED");
	}

	@Test
	void 연결과_갱신도_발행_확정이라_PUBLISHED_리비전을_쌓는다() throws Exception {
		act("confirm");
		UUID batch = publish();
		report(batch, "{\"ticketId\":\"%s\",\"processingType\":\"LINK\",\"status\":\"LINKED\",\"githubIssueNumber\":7,\"githubIssueUrl\":\"u7\"}".formatted(ticket));

		JsonNode h = history();
		assertThat(kinds(h)).containsExactly("AGENT", "PUBLISHED");
		assertThat(h.path("revisions").get(1).path("snapshot").path("result").path("status").asText()).isEqualTo("LINKED");
	}

	@Test
	void 발행_생략은_PUBLISHED_리비전을_쌓지_않는다() throws Exception {
		act("confirm");
		UUID batch = publish();
		report(batch, "{\"ticketId\":\"%s\",\"processingType\":\"CREATE\",\"status\":\"SKIPPED\",\"errorMessage\":\"발행하지 않음\"}".formatted(ticket));

		assertThat(items.findById(ticket).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.SKIPPED);
		JsonNode h = history();
		assertThat(kinds(h)).containsExactly("AGENT");
		assertThat(transitions(h)).contains("PUBLISHING>SKIPPED:PUBLISH_RESULT");
	}

	@Test
	void 발행_실패는_PUBLISHED_리비전을_쌓지_않고_수정하러_되돌리면_이력이_이어진다() throws Exception {
		act("confirm");
		UUID batch = publish();
		report(batch, "{\"ticketId\":\"%s\",\"processingType\":\"CREATE\",\"status\":\"FAILED\",\"errorCode\":\"GITHUB_FORBIDDEN\",\"errorMessage\":\"권한 없음\",\"retryable\":false}".formatted(ticket));
		act("revise");

		JsonNode h = history();
		assertThat(kinds(h)).containsExactly("AGENT");
		assertThat(transitions(h)).containsExactly(
				"null>PENDING_REVIEW:AGENT_RESULT",
				"PENDING_REVIEW>CONFIRMED:CONFIRM",
				"CONFIRMED>PUBLISHING:PUBLISH_REQUESTED",
				"PUBLISHING>FAILED:GITHUB_FORBIDDEN",
				"FAILED>PENDING_REVIEW:REVISE");
	}

	@Test
	void 결과_모름_실패를_반영됨으로_확인하면_PUBLISHED_리비전과_전이를_남긴다() throws Exception {
		mvc.perform(patch("/api/tickets/{id}/publication", ticket).session(me).contentType("application/json")
				.content("{\"publishTarget\":\"NOTION\"}")).andExpect(status().isOk());
		act("confirm");
		UUID batch = publish();
		report(batch, "{\"ticketId\":\"%s\",\"processingType\":\"CREATE\",\"status\":\"UNKNOWN\"}".formatted(ticket));
		assertThat(items.findById(ticket).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.FAILED);

		mvc.perform(post("/api/tickets/{id}/resolve-outcome", ticket).session(me).contentType("application/json")
				.content("{\"applied\":true,\"url\":\"https://app.notion.com/p/page\"}")).andExpect(status().isOk());

		JsonNode h = history();
		// 발행 대상을 바꾼 것도 사용자가 정한 값이라 확정할 때 수정본으로 남는다
		assertThat(kinds(h)).containsExactly("AGENT", "USER_EDIT", "PUBLISHED");
		assertThat(h.path("revisions").get(2).path("snapshot").path("result").path("url").asText()).isEqualTo("https://app.notion.com/p/page");
		assertThat(h.path("revisions").get(2).path("actor").path("name").asText()).isEqualTo("함주헌");
		assertThat(transitions(h).get(transitions(h).size() - 1)).isEqualTo("FAILED>PUBLISHED:OUTCOME_VERIFIED");
	}

	@Test
	void 결과_모름_실패를_미반영으로_확인하면_상태가_그대로라_리비전도_전이도_남기지_않는다() throws Exception {
		mvc.perform(patch("/api/tickets/{id}/publication", ticket).session(me).contentType("application/json")
				.content("{\"publishTarget\":\"NOTION\"}")).andExpect(status().isOk());
		act("confirm");
		report(publish(), "{\"ticketId\":\"%s\",\"processingType\":\"CREATE\",\"status\":\"UNKNOWN\"}".formatted(ticket));
		int before = transitions(history()).size();

		mvc.perform(post("/api/tickets/{id}/resolve-outcome", ticket).session(me).contentType("application/json")
				.content("{\"applied\":false}")).andExpect(status().isOk());

		JsonNode h = history();
		assertThat(kinds(h)).doesNotContain("PUBLISHED");
		assertThat(transitions(h)).hasSize(before);
	}

	@Test
	void 발행_접수가_취소되면_되돌린_전이도_남는다() throws Exception {
		act("confirm");
		when(gateway.submit(any())).thenThrow(new com.mcpcapstone.meettoticket.agent.AgentException(false));
		mvc.perform(post("/api/tickets/publish").session(me)).andExpect(status().isBadGateway());

		assertThat(transitions(history())).containsExactly(
				"null>PENDING_REVIEW:AGENT_RESULT",
				"PENDING_REVIEW>CONFIRMED:CONFIRM",
				"CONFIRMED>PUBLISHING:PUBLISH_REQUESTED",
				"PUBLISHING>CONFIRMED:PUBLISH_NOT_ACCEPTED");
		// 접수 취소로 확인 상태에 돌아와도 승인은 원래 확정한 사람/시각이다 (actor 없는 취소 전이가 승인으로 잡히면 안 된다)
		JsonNode h = history();
		assertThat(h.path("approval").path("actor").path("name").asText()).isEqualTo("함주헌");
		assertThat(h.path("approval").path("approvedAt").asText()).isEqualTo(h.path("statusHistory").get(1).path("createdAt").asText());
	}

	@Test
	void 담당자와_회의록을_올린_사람만_조회할_수_있다() throws Exception {
		User assignee = newUser();
		var project = projects.findById(projectId).orElseThrow();
		var nextMember = members.save(new ProjectMember(project, assignee, "새 담당", null));
		edit("{\"assigneeMemberId\":" + nextMember.getId() + "}");

		// 새 담당자와 회의록을 올린 사람(원래 담당자)은 본다
		mvc.perform(get("/api/tickets/{id}/history", ticket).session(userSession(assignee))).andExpect(status().isOk());
		mvc.perform(get("/api/tickets/{id}/history", ticket).session(me)).andExpect(status().isOk());
		// 담당자 변경은 확정할 때 USER_EDIT 스냅샷의 담당 멤버에 반영된다
		mvc.perform(post("/api/tickets/{id}/confirm", ticket).session(userSession(assignee))).andExpect(status().isOk());
		JsonNode h = json.read(mvc.perform(get("/api/tickets/{id}/history", ticket).session(me))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
		assertThat(kinds(h)).containsExactly("AGENT", "USER_EDIT");
		assertThat(h.path("revisions").get(1).path("snapshot").path("assignee_member_id").asLong()).isEqualTo(nextMember.getId());
		assertThat(h.path("revisions").get(1).path("actor").path("userId").asLong()).isEqualTo(assignee.getId());

		// 그 밖의 사용자는 403, 없는 티켓은 404, 로그인하지 않으면 401
		mvc.perform(get("/api/tickets/{id}/history", ticket).session(userSession(newUser())))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TICKET_HISTORY_FORBIDDEN"));
		mvc.perform(get("/api/tickets/{id}/history", UUID.randomUUID()).session(me))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
		mvc.perform(get("/api/tickets/{id}/history", ticket)).andExpect(status().isUnauthorized());
	}

	@Test
	void 프로젝트를_지우듯_티켓을_일괄_삭제하면_리비전과_상태_이력도_함께_지워진다() throws Exception {
		var other = projects.save(new Project("삭제 대상", "o" + UUID.randomUUID().toString().substring(0, 8), "r"));
		var user = users.findByLoginId("demo1").orElseThrow();
		members.save(new ProjectMember(other, user, "함주헌", null));
		UUID doomed = analyze(other.getId(), "함주헌");
		assertThat(revisions.findByTaskItem_IdOrderByRev(doomed)).hasSize(1);
		assertThat(statusHistory.findByTaskItem_IdOrderByCreatedAtAscIdAsc(doomed)).hasSize(1);

		// 프로젝트 삭제(ProjectDeletionService)와 같은 JPQL 일괄 삭제. 이력은 DB의 ON DELETE CASCADE로 지워진다.
		new TransactionTemplate(txManager).executeWithoutResult(status -> items.deleteByProjectId(other.getId()));

		assertThat(items.findById(doomed)).isEmpty();
		assertThat(revisions.findByTaskItem_IdOrderByRev(doomed)).isEmpty();
		assertThat(statusHistory.findByTaskItem_IdOrderByCreatedAtAscIdAsc(doomed)).isEmpty();
		// 다른 프로젝트의 이력은 그대로다
		assertThat(revisions.findByTaskItem_IdOrderByRev(ticket)).hasSize(1);
	}
}
