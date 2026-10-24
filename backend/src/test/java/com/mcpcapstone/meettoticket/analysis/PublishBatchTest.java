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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.MockPublishGateway;
import com.mcpcapstone.meettoticket.agent.PublishBatchLostException;
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
	@Autowired PublicationTicketMapper mapper;
	@Autowired PlatformTransactionManager txManager;

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

	@Test
	void 결과_콜백이_없어도_조회로_배치를_종료하며_발행은_재실행하지_않는다() throws Exception {
		TaskItem ticket = confirmed(project, "콜백 복구");
		UUID id = publish(null);
		var item = new PublishGateway.Item(ticket.getId().toString(), "CREATE", "PUBLISHED",
				19, "https://github.com/owner/repo/issues/19", null, List.of(), null, null, false, null, null, null);
		when(gateway.status(id.toString())).thenReturn(Optional.of(
				new PublishGateway.Result(id.toString(), "SUCCEEDED", Map.of(), List.of(item), null, null)));
		publishService.reconcileRunning(Duration.ofMinutes(1));
		assertThat(batches.findById(id).orElseThrow().getStatus()).isEqualTo(PublishBatchStatus.SUCCEEDED);
		assertThat(items.findById(ticket.getId()).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
		verify(gateway, times(1)).submit(any());
	}

	// #302: Agent가 재시작돼 묶음을 잊으면(GET /publish/{id} 404) 결과 대기 기한까지 기다리지 않고 결과 모름으로 끝낸다.
	// #299에서는 30분 기한이 지나야 끝났다. 막 접수한 묶음은 Agent가 등록하기 전일 수 있어 유예 시간 동안은 기다린다.
	@Test
	void Agent가_묶음을_모르면_유예_시간_뒤_결과_모름으로_바로_끝낸다() throws Exception {
		TaskItem ticket = confirmed(project, "Agent 재시작");
		UUID id = publish(null);
		// 테스트 DB에는 다른 테스트가 남긴 실행 중 묶음도 있다. 모두 잃은 묶음으로 답하게 해 한 주기 조회 수 제한과 무관하게 한다
		when(gateway.status(any())).thenAnswer(call -> { throw new PublishBatchLostException(call.getArgument(0)); });

		publishService.reconcileRunning(Duration.ofMinutes(1));
		assertThat(batches.findById(id).orElseThrow().getStatus()).isEqualTo(PublishBatchStatus.RUNNING);
		assertThat(items.findById(ticket.getId()).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);

		// 한 주기에 최대 10개씩 끝내므로 이 묶음이 끝날 때까지 주기를 반복한다
		for (int cycle = 0; cycle < 20 && batches.findById(id).orElseThrow().getStatus() == PublishBatchStatus.RUNNING; cycle++)
			publishService.reconcileRunning(Duration.ZERO);
		assertThat(batches.findById(id).orElseThrow().getStatus()).isEqualTo(PublishBatchStatus.FAILED);
		TaskItem lost = items.findById(ticket.getId()).orElseThrow();
		assertThat(lost.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(lost.getPublishErrorCode()).isEqualTo("RESULT_LOST");
		assertThat(lost.isPublishOutcomeUnknown()).isTrue();
		assertThat(lost.isPublishRetryable()).isTrue();
		verify(gateway, times(1)).submit(any());
	}

	// Agent가 묶음을 처리 중이라고 답하는데 결과가 오지 않으면 결과 대기 기한(app.publish.result-timeout)이 지나야 결과 모름으로 끝낸다.
	// Agent 발행 마감(PUBLISH_DEADLINE_SECONDS)이 정상 실행을 기한 안에 끝내므로, 이 경로는 Agent가 응답하지 않는 경우의 마지막 안전망이다.
	@Test
	void Agent가_결과를_주지_않으면_결과_대기_기한_뒤_결과_모름으로_끝낸다() throws Exception {
		TaskItem ticket = confirmed(project, "결과 없음");
		UUID id = publish(null);
		when(gateway.status(id.toString())).thenReturn(Optional.empty());

		publishService.reconcileRunning(Duration.ZERO);
		publishService.expireStale(Duration.ofMinutes(15));
		assertThat(batches.findById(id).orElseThrow().getStatus()).isEqualTo(PublishBatchStatus.RUNNING);
		assertThat(items.findById(ticket.getId()).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);

		publishService.expireStale(Duration.ZERO);
		assertThat(batches.findById(id).orElseThrow().getStatus()).isEqualTo(PublishBatchStatus.FAILED);
		TaskItem expired = items.findById(ticket.getId()).orElseThrow();
		assertThat(expired.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(expired.getPublishErrorCode()).isEqualTo("RESULT_TIMEOUT");
		assertThat(expired.isPublishOutcomeUnknown()).isTrue();
		verify(gateway, times(1)).submit(any());
	}

	// 실행 중인 묶음을 n개 만든다. 발행 한 번이 확인된 티켓 전부를 가져가므로 티켓 하나씩 발행한다.
	private void startBatches(int n) throws Exception {
		for (int i = 0; i < n; i++) {
			confirmed(project, "조회 대상 " + i);
			publish(null);
		}
	}

	@Test
	void Agent에_닿지_않으면_그_주기의_결과_조회를_바로_멈춘다() throws Exception {
		startBatches(2);
		when(gateway.status(any())).thenThrow(new AgentException(true));
		publishService.reconcileRunning(Duration.ofMinutes(1));
		// 묶음마다 타임아웃을 기다리지 않도록 첫 실패에서 멈춘다.
		verify(gateway, times(1)).status(any());
	}

	@Test
	void 한_묶음의_반영_오류는_다른_묶음의_조회를_막지_않는다() throws Exception {
		startBatches(2);
		when(gateway.status(any())).thenThrow(new IllegalStateException("반영 실패"));
		publishService.reconcileRunning(Duration.ofMinutes(1));
		verify(gateway, atLeast(2)).status(any());
	}

	@Test
	void 한_주기에_조회하는_묶음_수를_제한한다() throws Exception {
		startBatches(12);
		when(gateway.status(any())).thenReturn(Optional.empty());
		publishService.reconcileRunning(Duration.ofMinutes(1));
		verify(gateway, times(10)).status(any());
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
		assertThat(t.publishTarget()).isEqualTo("GITHUB");
		assertThat(t.slackMembers()).isEmpty();
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
	void Slack_공지는_GitHub_티켓과_함께_보내고_게시한_메시지_URL을_저장한다() throws Exception {
		TaskItem github = confirmed(project, "GitHub");
		TaskItem slack = confirmed(project, "Slack 공지");
		var slackItem = items.findById(slack.getId()).orElseThrow();
		ReflectionTestUtils.setField(slackItem, "publishTarget", PublishTarget.SLACK);
		items.save(slackItem);

		UUID batch = publish(null);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets()).extracting(PublishGateway.Ticket::ticketId)
				.containsExactlyInAnyOrder(github.getId().toString(), slack.getId().toString());
		assertThat(stateOf(slack)).isEqualTo(TicketStatus.PUBLISHING);

		// Agent는 Slack 공지를 POSTED와 messageUrl로 알려 준다 (TASK-051)
		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","summary":{"PUBLISHED":1,"POSTED":1},"items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","githubIssueNumber":7,
				  "githubIssueUrl":"https://github.com/owner/repo/issues/7","droppedLabels":[],"retryable":false},
				 {"ticketId":"%s","processingType":"CREATE","status":"POSTED",
				  "messageUrl":"https://team.slack.com/archives/C0123ABCD/p1","retryable":false}]}"""
				.formatted(batch, github.getId(), slack.getId()));

		var saved = items.findById(slack.getId()).orElseThrow();
		assertThat(saved.getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
		assertThat(saved.getGithubIssueUrl()).isEqualTo("https://team.slack.com/archives/C0123ABCD/p1");
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.summary.published").value(2))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].publishTarget".formatted(slack.getId())).value("SLACK"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='%s')].publishTarget".formatted(github.getId())).value("GITHUB"));
	}

	@Test
	void Slack_대상은_채널과_Slack_연결된_멤버를_Agent_계약대로_담는다() throws Exception {
		project.update(project.getName(), project.getDescription(), project.getGithubOwner(), project.getGithubRepo(),
				null, "C0123ABCD");
		projects.save(project);
		user.updateSlackUserId("U0SLACK1");
		users.save(user);
		members.save(new ProjectMember(project, newUser(), "미연결", "gh-other"));
		TaskItem slack = confirmed(project, "Slack 공지");
		var slackItem = items.findById(slack.getId()).orElseThrow();
		ReflectionTestUtils.setField(slackItem, "publishTarget", PublishTarget.SLACK);
		items.save(slackItem);

		// 회의, 프로젝트, 멤버를 지연 로딩하므로 트랜잭션 안에서 변환한다
		var sent = new TransactionTemplate(txManager).execute(status -> mapper.toTicket(items.findById(slack.getId()).orElseThrow()));

		assertThat(sent.publishTarget()).isEqualTo("SLACK");
		assertThat(sent.project().slackChannelId()).isEqualTo("C0123ABCD");
		assertThat(sent.slackMembers()).containsExactly(new PublishGateway.SlackMember(user.getName(), "U0SLACK1"));
		// Agent는 slackMembers 항목을 dict로 읽어 snake_case 키를 쓴다
		var wire = json.read(json.write(sent));
		assertThat(wire.path("slackMembers").get(0).path("slack_user_id").asText()).isEqualTo("U0SLACK1");
		assertThat(wire.path("project").path("slackChannelId").asText()).isEqualTo("C0123ABCD");
	}

	private TaskItem notionTicket(String title) {
		TaskItem t = confirmed(project, title);
		var item = items.findById(t.getId()).orElseThrow();
		ReflectionTestUtils.setField(item, "publishTarget", PublishTarget.NOTION);
		return items.save(item);
	}

	@Test
	void Notion_대상은_루트_페이지_URL과_함께_Agent로_보내고_만든_페이지_URL을_저장한다() throws Exception {
		project.update(project.getName(), project.getDescription(), project.getGithubOwner(), project.getGithubRepo(),
				"https://www.notion.so/root", null);
		projects.save(project);
		TaskItem notion = notionTicket("Notion 기획");

		UUID batch = publish(null);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		var sent = captor.getValue().tickets().get(0);
		assertThat(sent.ticketId()).isEqualTo(notion.getId().toString());
		assertThat(sent.project().notionPageUrl()).isEqualTo("https://www.notion.so/root");
		// 빠지면 Agent가 GITHUB로 처리해 Notion 기획이 GitHub 이슈로 만들어진다 (#250)
		assertThat(sent.publishTarget()).isEqualTo("NOTION");
		assertThat(sent.processingType()).isEqualTo("CREATE");

		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","summary":{"PUBLISHED":1},"items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","notionPageId":"page-1",
				  "notionPageUrl":"https://www.notion.so/page-1","droppedLabels":[],"retryable":false}]}"""
				.formatted(batch, notion.getId()));

		assertThat(stateOf(notion)).isEqualTo(TicketStatus.PUBLISHED);
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.tickets[0].githubIssueNumber").doesNotExist())
				.andExpect(jsonPath("$.tickets[0].githubIssueUrl").value("https://www.notion.so/page-1"));
	}

	@Test
	void Notion_루트_페이지가_없다는_보고는_실패로_기록한다() throws Exception {
		TaskItem notion = notionTicket("루트 없음");
		UUID batch = publish(null);

		report(batch, """
				{"batchId":"%s","status":"FAILED","summary":{"FAILED":1},"items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"FAILED","errorCode":"NOTION_ROOT_MISSING",
				  "errorMessage":"Notion 기획 페이지 URL이 없습니다","retryable":false}]}"""
				.formatted(batch, notion.getId()));

		var saved = items.findById(notion.getId()).orElseThrow();
		assertThat(saved.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(saved.getPublishErrorCode()).isEqualTo("NOTION_ROOT_MISSING");
		assertThat(saved.isPublishRetryable()).isFalse();
		assertThat(saved.getGithubIssueUrl()).isNull();
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
	void 발행을_생략한_결과는_성공이나_실패가_아닌_생략으로_저장한다() throws Exception {
		TaskItem item = confirmed(project, "발행 생략");
		UUID batch = publish(null);

		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","summary":{"SKIPPED":1},"items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"SKIPPED",
				  "errorMessage":"발행하지 않음: 외부 시스템에 변경을 만들지 않았습니다.","retryable":false}]}"""
				.formatted(batch, item.getId()));

		assertThat(stateOf(item)).isEqualTo(TicketStatus.SKIPPED);
		assertThat(items.findById(item.getId()).orElseThrow().getGithubIssueUrl()).isNull();
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.status").value("SUCCEEDED"))
				.andExpect(jsonPath("$.summary.skipped").value(1))
				.andExpect(jsonPath("$.summary.published").value(0));
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
	@Test
	void 발행_중에_티켓이_모두_지워진_묶음은_성공이_아니라_실패로_끝낸다() throws Exception {
		TaskItem a = confirmed(project, "프로젝트 삭제됨");
		UUID batch = publish(null);
		items.deleteById(a.getId());

		report(batch, "{\"batchId\":\"%s\",\"status\":\"SUCCEEDED\",\"items\":[]}".formatted(batch));

		assertThat(batches.findById(batch).orElseThrow().getStatus()).isEqualTo(PublishBatchStatus.FAILED);
	}
	private Long meetingId(TaskItem item) {
		return tasks.findById(item.getTask().getId()).orElseThrow().getMeeting().getId();
	}

	private TaskItem sameMeeting(TaskItem first, ProjectMember assignee, TicketStatus state) {
		var content = "{}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "같은 회의 티켓", json.read(content), "CREATE", null, "APPROVED", "SKIPPED", null, null, null);
		TaskItem added = new TaskItem(first.getTask(), item, content);
		added.assignTo(assignee);
		added.moveTo(state);
		return items.save(added);
	}

	@Test
	void 회의별_발행은_다른_회의와_다른_담당자와_거절을_제외한다() throws Exception {
		TaskItem a = confirmed(project, "이번 회의");
		TaskItem second = sameMeeting(a, member, TicketStatus.CONFIRMED);
		TaskItem rejected = sameMeeting(a, member, TicketStatus.REJECTED);
		User other = newUser();
		ProjectMember otherMember = members.save(new ProjectMember(project, other, other.getName(), "other"));
		TaskItem otherPending = sameMeeting(a, otherMember, TicketStatus.PENDING_REVIEW);
		TaskItem anotherMeeting = confirmed(project, "다른 회의");
		mvc.perform(post("/api/tickets/publish").session(session)
				.param("projectId", project.getId().toString()).param("meetingId", meetingId(a).toString())
				.param("ticketIds", a.getId() + "," + second.getId()))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.count").value(2));
		assertThat(stateOf(second)).isEqualTo(TicketStatus.PUBLISHING);
		assertThat(stateOf(a)).isEqualTo(TicketStatus.PUBLISHING);
		assertThat(stateOf(rejected)).isEqualTo(TicketStatus.REJECTED);
		assertThat(stateOf(otherPending)).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(stateOf(anotherMeeting)).isEqualTo(TicketStatus.CONFIRMED);
		mvc.perform(post("/api/tickets/publish").session(session)
				.param("meetingId", meetingId(a).toString()).param("ticketIds", a.getId().toString()))
				.andExpect(status().isConflict());
		verify(gateway, times(1)).submit(any());
	}

	@Test
	void 회의의_본인_검토가_남아있으면_발행하지_않는다() throws Exception {
		TaskItem a = confirmed(project, "검토 중");
		sameMeeting(a, member, TicketStatus.PENDING_REVIEW);
		mvc.perform(post("/api/tickets/publish").session(session)
				.param("meetingId", meetingId(a).toString()).param("ticketIds", a.getId().toString()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MEETING_REVIEW_INCOMPLETE"));
		assertThat(stateOf(a)).isEqualTo(TicketStatus.CONFIRMED);
		verify(gateway, never()).submit(any());
	}

	@Test
	void 확인창_이후_대상_변경이나_다른_담당자의_회의는_발행하지_않는다() throws Exception {
		TaskItem a = confirmed(project, "대상 변경");
		sameMeeting(a, member, TicketStatus.CONFIRMED);
		mvc.perform(post("/api/tickets/publish").session(session)
				.param("meetingId", meetingId(a).toString()).param("ticketIds", a.getId().toString()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLISH_SELECTION_CHANGED"));
		mvc.perform(post("/api/tickets/publish").session(session(newUser()))
				.param("meetingId", meetingId(a).toString()).param("ticketIds", a.getId().toString()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CONFIRMED_TICKETS"));
		verify(gateway, never()).submit(any());
	}

	@Test
	void 회의_필터의_잘못된_입력과_다른_프로젝트를_거절한다() throws Exception {
		TaskItem a = confirmed(project, "도구 검증");
		var meetingId = meetingId(a).toString();
		mvc.perform(post("/api/tickets/publish").session(session).param("meetingId", "invalid"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/tickets/publish").session(session).param("meetingId", meetingId))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLISH_SELECTION_CHANGED"));
		mvc.perform(post("/api/tickets/publish").session(session).param("meetingId", meetingId)
				.param("projectId", newProject().getId().toString()).param("ticketIds", a.getId().toString()))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CONFIRMED_TICKETS"));
		verify(gateway, never()).submit(any());
	}

	@Test
	void 회의별_발행도_Slack_공지를_함께_보낸다() throws Exception {
		TaskItem a = confirmed(project, "회의 Slack 공지");
		var item = items.findById(a.getId()).orElseThrow();
		ReflectionTestUtils.setField(item, "publishTarget", PublishTarget.SLACK);
		items.save(item);

		mvc.perform(post("/api/tickets/publish").session(session)
						.param("meetingId", meetingId(a).toString()).param("ticketIds", a.getId().toString()))
				.andExpect(status().isAccepted());

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets()).extracting(PublishGateway.Ticket::publishTarget).containsExactly("SLACK");
	}

}
