package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.MockPublishGateway;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 확인한 티켓 일괄 발행과 발행 결과 저장 (REQ-016, REQ-018, TASK-046). 테스트마다 새 사용자와 프로젝트를 만들어 서로 영향을 주지 않는다.
@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=publish-test-token",
		"spring.datasource.url=jdbc:h2:mem:publish-batches;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishBatchTest {
	private static final String TOKEN = "publish-test-token";

	@MockitoBean PublishGateway gateway;
	@Autowired MockMvc mvc;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;
	@Autowired PublishService publishService;
	@Autowired PublishBatchRepository batches;

	private User user;
	private MockHttpSession session;
	private Project project;
	private ProjectMember member;

	@BeforeEach
	void setUp() {
		user = newUser();
		session = session(user);
		project = newProject();
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
		when(gateway.mode()).thenReturn("LIVE");
		when(gateway.submit(any())).thenReturn(Optional.empty()); // 접수만 하고 결과는 나중에 보고된다
	}

	private User newUser() {
		return users.save(new User("pb" + UUID.randomUUID().toString().substring(0, 8), "hash", "발행자"));
	}

	private MockHttpSession session(User u) {
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(SessionConst.LOGIN_USER_ID, u.getId());
		return s;
	}

	private Project newProject() {
		return projects.save(new Project("발행 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
	}

	// 내가 담당인 티켓 하나를 주어진 상태로 만든다.
	private TaskItem ticket(Project p, ProjectMember assignee, String title, TicketStatus status, String contentJson) {
		return ticket(p, assignee, title, status, contentJson, "CREATE");
	}

	// action은 Agent 값(CREATE, DUPLICATE는 연결, UPDATE)이며 처리 유형으로 옮겨진다.
	private TaskItem ticket(Project p, ProjectMember assignee, String title, TicketStatus status, String contentJson, String action) {
		Meeting meeting = meetings.save(new Meeting(p, user, "회의 " + title, LocalDate.of(2026, 10, 22), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		var item = new AgentContracts.Item(UUID.randomUUID(), null, title, json.read(contentJson), action, "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, contentJson);
		t.assignTo(assignee);
		t.moveTo(status);
		return items.save(t);
	}

	private TaskItem confirmed(Project p, String title) {
		return ticket(p, member, title, TicketStatus.CONFIRMED, "{\"description\":\"본문\",\"dedupe\":null}");
	}

	private UUID publish(Long projectId) throws Exception {
		var req = post("/api/tickets/publish").session(session);
		if (projectId != null) req.param("projectId", String.valueOf(projectId));
		String body = mvc.perform(req).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.read(body).path("batchId").asText());
	}

	private void report(UUID batchId, String body) throws Exception {
		mvc.perform(post("/internal/publish-batches/{id}/result", batchId).header("X-Internal-Token", TOKEN)
						.contentType("application/json").content(body))
				.andExpect(status().isAccepted());
	}

	private TicketStatus stateOf(TaskItem t) {
		return items.findById(t.getId()).orElseThrow().getTicketStatus();
	}

	@Test
	void 확인한_티켓만_발행_중으로_보내고_중복_클릭은_막는다() throws Exception {
		TaskItem a = confirmed(project, "확인");
		TaskItem pending = ticket(project, member, "검토 대기", TicketStatus.PENDING_REVIEW, "{}");
		TaskItem rejected = ticket(project, member, "거절", TicketStatus.REJECTED, "{}");

		UUID batch = publish(null);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		var sent = captor.getValue();
		assertThat(sent.batchId()).isEqualTo(batch.toString());
		assertThat(sent.tickets()).hasSize(1);
		var t = sent.tickets().get(0);
		assertThat(t.ticketId()).isEqualTo(a.getId().toString());
		assertThat(t.reviewStatus()).isEqualTo("CONFIRMED");
		assertThat(t.processingType()).isEqualTo("CREATE");
		assertThat(t.project().githubOwner()).isEqualTo("owner");
		assertThat(t.contentJson().path("description").asText()).isEqualTo("본문");

		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHING);
		assertThat(stateOf(pending)).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(stateOf(rejected)).isEqualTo(TicketStatus.REJECTED);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.summary.publishing").value(1));

		// 발행 중인 티켓은 다시 담을 수 없다
		mvc.perform(post("/api/tickets/publish").session(session))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CONFIRMED_TICKETS"));
		verify(gateway, times(1)).submit(any());
	}

	@Test
	void GitHub_이외_발행_대상은_보내지_않고_확인_상태로_둔다() throws Exception {
		TaskItem github = confirmed(project, "GitHub");
		TaskItem slack = confirmed(project, "Slack 공지");
		var slackItem = items.findById(slack.getId()).orElseThrow();
		ReflectionTestUtils.setField(slackItem, "publishTarget", PublishTarget.SLACK);
		items.save(slackItem);

		publish(null);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets()).extracting(PublishGateway.Ticket::ticketId).containsExactly(github.getId().toString());
		assertThat(stateOf(slack)).isEqualTo(TicketStatus.CONFIRMED);
		// GitHub 티켓이 없으면 담을 것이 없다
		mvc.perform(post("/api/tickets/publish").session(session)).andExpect(status().isConflict());
	}

	@Test
	void Agent_보고를_티켓_상태와_이슈_링크로_저장한다() throws Exception {
		TaskItem a = confirmed(project, "신규");
		UUID batch = publish(null);

		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","summary":{"PUBLISHED":1},"items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","githubIssueNumber":42,
				  "githubIssueUrl":"https://github.com/owner/repo/issues/42","droppedLabels":[],"retryable":false}]}"""
				.formatted(batch, a.getId()));

		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHED);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.summary.total").value(1))
				.andExpect(jsonPath("$.summary.published").value(1))
				.andExpect(jsonPath("$.tickets[0].githubIssueNumber").value(42))
				.andExpect(jsonPath("$.tickets[0].githubIssueUrl").value("https://github.com/owner/repo/issues/42"));

		// 같은 보고가 다시 와도 결과를 바꾸지 않는다
		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED","errorCode":"X"}]}"""
				.formatted(batch, a.getId()));
		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHED);
	}

	@Test
	void 일부_실패와_재대조로_바뀐_처리_유형을_저장하고_실패_건수를_알린다() throws Exception {
		Project other = newProject();
		ProjectMember otherMember = members.save(new ProjectMember(other, user, user.getName(), "gh-user"));
		TaskItem created = confirmed(project, "신규");
		TaskItem relinked = ticket(project, member, "연결로 바뀜", TicketStatus.CONFIRMED, "{}");
		TaskItem forbidden = ticket(other, otherMember, "권한 없음", TicketStatus.CONFIRMED, "{}");
		UUID batch = publish(null);

		report(batch, """
				{"batchId":"%s","status":"PARTIAL_FAILED","items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","githubIssueNumber":1,"githubIssueUrl":"u1"},
				 {"ticketId":"%s","processingType":"LINK","status":"LINKED","githubIssueNumber":7,"githubIssueUrl":"u7"},
				 {"ticketId":"%s","processingType":"CREATE","status":"FAILED","errorCode":"GITHUB_FORBIDDEN","errorMessage":"권한 없음","retryable":false}]}"""
				.formatted(batch, created.getId(), relinked.getId(), forbidden.getId()));

		assertThat(stateOf(created)).isEqualTo(TicketStatus.PUBLISHED);
		assertThat(stateOf(relinked)).isEqualTo(TicketStatus.LINKED);
		assertThat(stateOf(forbidden)).isEqualTo(TicketStatus.FAILED);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("PARTIAL_FAILED"))
				.andExpect(jsonPath("$.summary.total").value(3))
				.andExpect(jsonPath("$.summary.published").value(1))
				.andExpect(jsonPath("$.summary.linked").value(1))
				.andExpect(jsonPath("$.summary.failed").value(1))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + relinked.getId() + "')].processingType").value("LINK"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + forbidden.getId() + "')].errorCode").value("GITHUB_FORBIDDEN"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + forbidden.getId() + "')].retryable").value(false));
		mvc.perform(get("/api/tickets/me").session(session))
				.andExpect(jsonPath("$.counts.failed").value(1));
	}

	@Test
	void 결과_모름과_보고에_빠진_티켓은_실패로_두되_결과_모름으로_구분한다() throws Exception {
		TaskItem unknown = confirmed(project, "응답 없음");
		TaskItem missing = confirmed(project, "보고 누락");
		UUID batch = publish(null);

		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"UNKNOWN","errorCode":"RESULT_UNKNOWN","errorMessage":"시간 초과","retryable":true}]}"""
				.formatted(batch, unknown.getId()));

		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + unknown.getId() + "')].status").value("FAILED"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + unknown.getId() + "')].outcomeUnknown").value(true))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + missing.getId() + "')].errorCode").value("RESULT_MISSING"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + missing.getId() + "')].outcomeUnknown").value(true));
	}

	@Test
	void 프로젝트로_거르면_그_프로젝트의_확인된_티켓만_보낸다() throws Exception {
		Project other = newProject();
		ProjectMember otherMember = members.save(new ProjectMember(other, user, user.getName(), "gh-user"));
		TaskItem mine = confirmed(project, "이 프로젝트");
		TaskItem elsewhere = ticket(other, otherMember, "다른 프로젝트", TicketStatus.CONFIRMED, "{}");

		publish(project.getId());

		assertThat(stateOf(mine)).isEqualTo(TicketStatus.PUBLISHING);
		assertThat(stateOf(elsewhere)).isEqualTo(TicketStatus.CONFIRMED);
	}

	@Test
	void 연결과_갱신은_중복_판단의_기존_이슈_번호를_보내고_번호가_없으면_신규로_보낸다() throws Exception {
		TaskItem link = ticket(project, member, "연결", TicketStatus.CONFIRMED, "{\"dedupe\":{\"issue_number\":12}}", "DUPLICATE");
		TaskItem noNumber = ticket(project, member, "번호 없음", TicketStatus.CONFIRMED, "{\"dedupe\":null}", "UPDATE");

		publish(null);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		var byId = captor.getValue().tickets().stream().collect(java.util.stream.Collectors.toMap(PublishGateway.Ticket::ticketId, x -> x));
		assertThat(byId.get(link.getId().toString()).processingType()).isEqualTo("LINK");
		assertThat(byId.get(link.getId().toString()).targetIssueNumber()).isEqualTo(12);
		assertThat(byId.get(noNumber.getId().toString()).processingType()).isEqualTo("CREATE");
		assertThat(byId.get(noNumber.getId().toString()).targetIssueNumber()).isNull();
	}

	@Test
	void Agent가_접수하지_않은_것이_확실하면_확인_상태로_돌려_다시_누를_수_있다() throws Exception {
		TaskItem a = confirmed(project, "접수 실패");
		when(gateway.submit(any())).thenThrow(new AgentException(false));

		mvc.perform(post("/api/tickets/publish").session(session))
				.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AGENT_ACCEPTANCE_FAILED"));
		assertThat(stateOf(a)).isEqualTo(TicketStatus.CONFIRMED);

		doReturn(Optional.empty()).when(gateway).submit(any()); // 이미 예외를 던지도록 설정돼 있어 doReturn으로 바꾼다
		publish(null);
		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHING);
	}

	@Test
	void 접수_여부를_모르면_발행_중으로_두고_결과를_기다린다() throws Exception {
		TaskItem a = confirmed(project, "시간 초과");
		when(gateway.submit(any())).thenThrow(new AgentException(true));

		UUID batch = publish(null);

		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHING);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session)).andExpect(jsonPath("$.status").value("RUNNING"));
	}

	@Test
	void 모의_실행은_즉시_결과를_저장하되_이슈_링크는_없다() throws Exception {
		TaskItem a = confirmed(project, "모의");
		when(gateway.mode()).thenReturn("MOCK");
		when(gateway.submit(any())).thenAnswer(i -> new MockPublishGateway().submit(i.getArgument(0)));

		UUID batch = publish(null);

		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHED);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.executionMode").value("MOCK"))
				.andExpect(jsonPath("$.tickets[0].githubIssueUrl").value(nullValue()));
	}

	@Test
	void 다른_사용자는_발행_결과를_보지_못하고_토큰_없는_보고는_거절한다() throws Exception {
		TaskItem a = confirmed(project, "비공개");
		UUID batch = publish(null);

		mvc.perform(get("/api/publish-batches/{id}", batch).session(session(newUser())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PUBLISH_BATCH_NOT_FOUND"));
		mvc.perform(get("/api/publish-batches/{id}", batch)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/tickets/publish")).andExpect(status().isUnauthorized());
		for (var header : List.of(Map.<String, String>of(), Map.of("X-Internal-Token", "wrong"), Map.of("Authorization", "Bearer wrong"))) {
			var req = post("/internal/publish-batches/{id}/result", batch).session(session).contentType("application/json")
					.content("{\"batchId\":\"%s\",\"status\":\"SUCCEEDED\",\"items\":[]}".formatted(batch));
			header.forEach(req::header);
			mvc.perform(req).andExpect(status().isUnauthorized());
		}
		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHING);
		mvc.perform(get("/api/publish-batches/{id}", UUID.randomUUID()).session(session)).andExpect(status().isNotFound());
	}
	@Test
	void 기한_안에_결과가_오지_않은_묶음은_결과_모름으로_끝내고_늦은_보고는_무시한다() throws Exception {
		TaskItem stale = confirmed(project, "보고 없음");
		when(gateway.submit(any())).thenThrow(new AgentException(true));
		UUID batch = publish(null);
		var saved = batches.findById(batch).orElseThrow();
		ReflectionTestUtils.setField(saved, "createdAt", OffsetDateTime.now().minusHours(1));
		batches.save(saved);
		TaskItem recent = confirmed(project, "방금 보냄");
		UUID running = publish(null);

		publishService.expireStale(Duration.ofMinutes(30));

		assertThat(stateOf(stale)).isEqualTo(TicketStatus.FAILED);
		assertThat(stateOf(recent)).isEqualTo(TicketStatus.PUBLISHING);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.tickets[0].errorCode").value("RESULT_TIMEOUT"))
				.andExpect(jsonPath("$.tickets[0].outcomeUnknown").value(true));
		mvc.perform(get("/api/publish-batches/{id}", running).session(session)).andExpect(jsonPath("$.status").value("RUNNING"));

		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","items":[{"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","githubIssueNumber":3}]}"""
				.formatted(batch, stale.getId()));
		assertThat(stateOf(stale)).isEqualTo(TicketStatus.FAILED);
	}

	@Test
	void 담당자가_Agent_제안과_다른_처리_유형을_고르면_고친_값으로_알린다() throws Exception {
		String content = "{\"dedupe\":{\"issue_number\":12,\"issue_state\":\"open\",\"handling\":{\"type\":\"LINK\"}}}";
		TaskItem kept = ticket(project, member, "제안 그대로", TicketStatus.CONFIRMED, content, "DUPLICATE");
		TaskItem changed = ticket(project, member, "신규로 바꿈", TicketStatus.CONFIRMED, content, "DUPLICATE");
		var item = items.findById(changed.getId()).orElseThrow();
		ReflectionTestUtils.setField(item, "processingType", ProcessingType.CREATE);
		items.save(item);

		publish(null);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		var byId = captor.getValue().tickets().stream().collect(java.util.stream.Collectors.toMap(PublishGateway.Ticket::ticketId, x -> x));
		assertThat(byId.get(kept.getId().toString()).processingTypeEdited()).isFalse();
		assertThat(byId.get(changed.getId().toString()).processingType()).isEqualTo("CREATE");
		assertThat(byId.get(changed.getId().toString()).processingTypeEdited()).isTrue();
	}
}
