package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 발행 흐름이 발행 기록(publications)의 예약, 접수, 결과 상태를 남기고 중복 발행을 막는지 확인한다 (#263).
@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=publication-flow-token",
		"spring.datasource.url=jdbc:h2:mem:publication-flow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicationFlowTest {
	private static final String TOKEN = "publication-flow-token";

	@MockitoBean PublishGateway gateway;
	@Autowired MockMvc mvc;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired PublicationRepository publications;
	@Autowired PublishService publishService;
	@Autowired JsonCodec json;

	private User user;
	private MockHttpSession session;
	private Project project;
	private ProjectMember member;

	@BeforeEach
	void setUp() {
		user = users.save(new User("pf" + UUID.randomUUID().toString().substring(0, 8), "hash", "발행 흐름"));
		session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		project = projects.save(new Project("흐름 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
		when(gateway.mode()).thenReturn("LIVE");
		when(gateway.submit(any())).thenReturn(Optional.empty()); // 접수만 하고 결과는 나중에 보고된다
	}

	private TaskItem ticket(TicketStatus status, PublishTarget target) {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 23), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "흐름 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		t.changePublication(target, ProcessingType.CREATE);
		t.moveTo(status);
		return items.save(t);
	}

	private TaskItem confirmed() { return ticket(TicketStatus.CONFIRMED, PublishTarget.GITHUB); }

	private UUID publish() throws Exception {
		String body = mvc.perform(post("/api/tickets/publish").param("projectId", String.valueOf(project.getId())).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.read(body).path("batchId").asText());
	}

	private UUID retry(TaskItem t) throws Exception {
		String body = mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.read(body).path("batchId").asText());
	}

	private void report(UUID batchId, String body) throws Exception {
		mvc.perform(post("/internal/publish-batches/{id}/result", batchId).header("X-Internal-Token", TOKEN)
						.contentType("application/json").content(body))
				.andExpect(status().isAccepted());
	}

	private void report(UUID batch, TaskItem t, String item) throws Exception {
		report(batch, "{\"batchId\":\"%s\",\"status\":\"SUCCEEDED\",\"items\":[{\"ticketId\":\"%s\",%s}]}".formatted(batch, t.getId(), item));
	}

	private TaskItem reload(TaskItem t) { return items.findById(t.getId()).orElseThrow(); }

	private Publication row(TaskItem t, PublishOperation op) {
		return publications.findByTargetAndIdempotencyKey(t.getPublishTarget(), Publication.keyOf(t.getId(), t.getPublishTarget(), op)).orElseThrow();
	}

	private Publication row(TaskItem t) { return row(t, PublishOperation.CREATE); }

	private static final String PUBLISHED = "\"processingType\":\"CREATE\",\"status\":\"PUBLISHED\",\"githubIssueNumber\":7,\"githubIssueUrl\":\"https://github.com/owner/repo/issues/7\"";
	private static final String UNKNOWN = "\"processingType\":\"CREATE\",\"status\":\"UNKNOWN\",\"errorCode\":\"RESULT_UNKNOWN\",\"errorMessage\":\"연결 끊김\"";
	private static final String FAILED = "\"processingType\":\"CREATE\",\"status\":\"FAILED\",\"errorCode\":\"GITHUB_RATE_LIMIT\",\"errorMessage\":\"잠시 후\",\"retryable\":true";

	@Test
	void 발행하면_Agent에_보내기_전에_예약하고_접수되면_SENT_결과가_오면_CONFIRMED로_바뀐다() throws Exception {
		TaskItem t = confirmed();
		// Agent가 호출되는 순간에 이미 RESERVED가 커밋돼 있어야 한다
		var seenAtSubmit = new java.util.concurrent.atomic.AtomicReference<PublicationStatus>();
		when(gateway.submit(any())).thenAnswer(inv -> {
			seenAtSubmit.set(row(t).getStatus());
			return Optional.empty();
		});

		UUID batch = publish();

		assertThat(seenAtSubmit.get()).isEqualTo(PublicationStatus.RESERVED);
		var sent = row(t);
		assertThat(sent.getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(sent.getPublishBatchId()).isEqualTo(batch);
		assertThat(sent.getSentAt()).isNotNull();
		assertThat(sent.getAttemptCount()).isEqualTo(1);

		report(batch, t, PUBLISHED);

		var done = row(t);
		assertThat(done.getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(done.getExternalId()).isEqualTo("7");
		assertThat(done.getExternalUrl()).isEqualTo("https://github.com/owner/repo/issues/7");
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
		// Agent가 같은 결과를 다시 보고해도 그대로다
		report(batch, t, PUBLISHED);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
	}

	@Test
	void 접수와_함께_결과가_바로_오는_실행에서도_CONFIRMED로_끝난다() throws Exception {
		TaskItem t = confirmed();
		when(gateway.submit(any())).thenAnswer(inv -> {
			PublishGateway.Request request = inv.getArgument(0);
			return Optional.of(new PublishGateway.Result(request.batchId(), "SUCCEEDED", java.util.Map.of(), List.of(
					new PublishGateway.Item(t.getId().toString(), "CREATE", "PUBLISHED", 9, "https://github.com/owner/repo/issues/9",
							null, List.of(), null, null, false, null, null, null)), null, null));
		});

		publish();

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(t).getExternalId()).isEqualTo("9");
	}

	@Test
	void 반영되지_않은_실패는_FAILED로_남고_같은_키로_다시_시도하면_시도_횟수만_늘어난다() throws Exception {
		TaskItem t = confirmed();
		UUID first = publish();
		report(first, t, FAILED);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.FAILED);
		assertThat(row(t).getErrorCode()).isEqualTo("GITHUB_RATE_LIMIT");

		UUID second = retry(reload(t));

		var again = row(t);
		assertThat(again.getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(again.getAttemptCount()).isEqualTo(2);
		assertThat(again.getPublishBatchId()).isEqualTo(second);
		assertThat(again.getErrorCode()).isNull();
		report(second, t, PUBLISHED);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
	}

	@Test
	void 결과_모름은_UNCERTAIN이고_GitHub는_확인_뒤_재대조와_함께_다시_보낼_수_있다() throws Exception {
		TaskItem t = confirmed();
		UUID batch = publish();

		report(batch, t, UNKNOWN);

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(t).getErrorCode()).isEqualTo("RESULT_UNKNOWN");
		retry(reload(t));
		assertThat(row(t).getAttemptCount()).isEqualTo(2);
		var captor = org.mockito.ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway, times(2)).submit(captor.capture());
		assertThat(captor.getValue().tickets().getFirst().previousOutcomeUnknown()).isTrue();
	}

	@Test
	void Notion_결과_모름은_확인_결과를_남기기_전에는_다시_보내지_않고_미반영_확인_뒤에는_보낸다() throws Exception {
		TaskItem t = ticket(TicketStatus.CONFIRMED, PublishTarget.NOTION);
		UUID batch = publish();
		report(batch, t, UNKNOWN);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);

		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session)).andExpect(status().isConflict());
		verify(gateway, times(1)).submit(any());
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);

		// 확인 결과 없이 티켓을 다시 확정해 발행해도 발행 기록이 막는다 (티켓 상태 검사를 우회한 경우의 방어)
		TaskItem forced = reload(t);
		forced.moveTo(TicketStatus.CONFIRMED);
		items.save(forced);
		mvc.perform(post("/api/tickets/publish").param("projectId", String.valueOf(project.getId())).session(session))
				.andExpect(status().isConflict());
		verify(gateway, times(1)).submit(any());
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.CONFIRMED);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		forced = reload(t);
		forced.recordPublishResult(TicketStatus.FAILED, null, null, null, "RESULT_UNKNOWN", "결과 모름", true, true);
		items.save(forced);

		mvc.perform(post("/api/tickets/{id}/resolve-outcome", t.getId()).session(session)
				.contentType("application/json").content("{\"applied\":false}")).andExpect(status().isOk());
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.FAILED);
		assertThat(row(t).getErrorCode()).isEqualTo(TaskItem.VERIFIED_NOT_APPLIED);

		retry(reload(t));
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(row(t).getAttemptCount()).isEqualTo(2);
		verify(gateway, times(2)).submit(any());
	}

	@Test
	void 결과_모름을_반영됨으로_확인하면_CONFIRMED가_되어_다시_보낼_수_없다() throws Exception {
		TaskItem t = ticket(TicketStatus.CONFIRMED, PublishTarget.SLACK);
		report(publish(), t, UNKNOWN);

		mvc.perform(post("/api/tickets/{id}/resolve-outcome", t.getId()).session(session)
				.contentType("application/json").content("{\"applied\":true,\"url\":\"https://team.slack.com/archives/C1/p1\"}")).andExpect(status().isOk());

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(t).getExternalUrl()).isEqualTo("https://team.slack.com/archives/C1/p1");
		// 티켓이 어떤 이유로 다시 확인 상태가 돼도 같은 키는 외부에 두 번 쓰이지 않는다
		TaskItem forced = reload(t);
		forced.moveTo(TicketStatus.CONFIRMED);
		items.save(forced);
		mvc.perform(post("/api/tickets/publish").param("projectId", String.valueOf(project.getId())).session(session))
				.andExpect(status().isConflict());
		verify(gateway, times(1)).submit(any());
	}

	@Test
	void 발행_생략은_CONFIRMED로_기록하지_않는다() throws Exception {
		TaskItem t = confirmed();
		UUID batch = publish();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);

		report(batch, t, "\"processingType\":\"CREATE\",\"status\":\"SKIPPED\",\"errorMessage\":\"발행하지 않음\"");

		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.SKIPPED);
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).isEmpty();
	}

	@Test
	void Agent가_접수하지_않으면_예약을_FAILED로_풀고_티켓을_되돌려_다시_발행할_수_있다() throws Exception {
		TaskItem t = confirmed();
		when(gateway.submit(any())).thenThrow(new AgentException(false));

		mvc.perform(post("/api/tickets/publish").param("projectId", String.valueOf(project.getId())).session(session))
				.andExpect(status().isBadGateway());

		var released = row(t);
		assertThat(released.getStatus()).isEqualTo(PublicationStatus.FAILED);
		assertThat(released.getErrorCode()).isEqualTo(PublicationLedger.CODE_NOT_ACCEPTED);
		assertThat(released.getSentAt()).isNull();
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.CONFIRMED);

		doReturn(Optional.empty()).when(gateway).submit(any());
		publish();
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(row(t).getAttemptCount()).isEqualTo(2);
	}

	@Test
	void 접수_여부를_모르면_SENT로_두고_결과를_기다린다() throws Exception {
		TaskItem t = confirmed();
		when(gateway.submit(any())).thenThrow(new AgentException(true));

		UUID batch = publish();

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.SENT);
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);
		// 기한 안에 보고가 없으면 결과 모름으로 끝난다
		assertThat(publishService.expireStale(Duration.ZERO)).isGreaterThanOrEqualTo(1);
		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(t).getErrorCode()).isEqualTo("RESULT_TIMEOUT");
		assertThat(batch).isNotNull();
	}

	@Test
	void 보고에_빠진_티켓은_UNCERTAIN으로_남는다() throws Exception {
		TaskItem t = confirmed();
		UUID batch = publish();

		report(batch, "{\"batchId\":\"%s\",\"status\":\"FAILED\",\"items\":[],\"errorCode\":\"INTERNAL_ERROR\",\"errorMessage\":\"연결 실패\"}".formatted(batch));

		assertThat(row(t).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(t).getErrorCode()).isEqualTo("INTERNAL_ERROR");
	}

	@Test
	void 같은_티켓을_동시에_발행해도_예약은_한_번이고_Agent에도_한_번만_간다() throws Exception {
		TaskItem t = confirmed();
		var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
		try {
			java.util.concurrent.Callable<Integer> call = () -> mvc.perform(post("/api/tickets/publish")
					.param("projectId", String.valueOf(project.getId())).session(session)).andReturn().getResponse().getStatus();
			var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(call)).toList();
			var statuses = new java.util.ArrayList<Integer>();
			for (var f : futures) statuses.add(f.get());

			assertThat(statuses).containsOnlyOnce(202).filteredOn(s -> s != 202).containsOnly(409);
			verify(gateway, times(1)).submit(any());
			assertThat(publications.findByTaskItem_IdOrderById(t.getId())).hasSize(1);
			assertThat(row(t).getAttemptCount()).isEqualTo(1);
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void 갱신은_본문과_알림_댓글을_별도_작업으로_기록한다() throws Exception {
		TaskItem t = confirmed();
		t.changePublication(PublishTarget.GITHUB, ProcessingType.UPDATE);
		t = items.save(t);
		// 갱신 대상 이슈를 알아야 UPDATE로 보낸다
		String content = "{\"description\":\"본문\",\"dedupe\":{\"issue_number\":73}}";
		t.edit(null, content);
		items.save(t);
		final TaskItem target = t;

		UUID batch = publish();
		report(batch, target, "\"processingType\":\"UPDATE\",\"status\":\"UPDATED\",\"githubIssueNumber\":73,\"githubIssueUrl\":\"https://github.com/owner/repo/issues/73\","
				+ "\"errorCode\":\"COMMENT_FAILED\",\"errorMessage\":\"댓글 오류\",\"retryable\":true");

		// 본문 갱신은 확정이다. 댓글 실패는 별도 작업(COMMENT)으로 다시 보낸다.
		assertThat(row(target, PublishOperation.UPDATE).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(reload(target).isCommentRetry()).isTrue();
		assertThat(publications.findByTaskItem_IdOrderById(target.getId())).hasSize(1);

		String body = mvc.perform(post("/api/tickets/{id}/retry-comment", target.getId()).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID commentBatch = UUID.fromString(json.read(body).path("batchId").asText());
		assertThat(row(target, PublishOperation.COMMENT).getStatus()).isEqualTo(PublicationStatus.SENT);

		// 댓글이 다시 실패하면 FAILED로 두고 한 번 더 보낼 수 있다
		report(commentBatch, target, "\"processingType\":\"UPDATE\",\"status\":\"FAILED\",\"errorCode\":\"COMMENT_FAILED\",\"errorMessage\":\"또 실패\",\"retryable\":true");
		assertThat(row(target, PublishOperation.COMMENT).getStatus()).isEqualTo(PublicationStatus.FAILED);
		body = mvc.perform(post("/api/tickets/{id}/retry-comment", target.getId()).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		commentBatch = UUID.fromString(json.read(body).path("batchId").asText());
		assertThat(row(target, PublishOperation.COMMENT).getAttemptCount()).isEqualTo(2);

		report(commentBatch, target, "\"processingType\":\"UPDATE\",\"status\":\"UPDATED\",\"githubIssueNumber\":73");
		assertThat(row(target, PublishOperation.COMMENT).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(row(target, PublishOperation.UPDATE).getStatus()).isEqualTo(PublicationStatus.CONFIRMED);
		assertThat(publications.findByTaskItem_IdOrderById(target.getId())).hasSize(2);
	}

	@Test
	void 댓글_표식을_끝까지_확인하지_못하면_COMMENT는_UNCERTAIN이다() throws Exception {
		TaskItem t = ticket(TicketStatus.UPDATED, PublishTarget.GITHUB);
		t.recordPublishResult(TicketStatus.UPDATED, ProcessingType.UPDATE, 73, "https://github.com/owner/repo/issues/73", "COMMENT_FAILED", "댓글 오류", true, false);
		t = items.save(t);
		final TaskItem target = t;

		String body = mvc.perform(post("/api/tickets/{id}/retry-comment", target.getId()).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID batch = UUID.fromString(json.read(body).path("batchId").asText());
		report(batch, target, "\"processingType\":\"UPDATE\",\"status\":\"UPDATED\",\"errorCode\":\"MARKER_CHECK_INCOMPLETE\",\"errorMessage\":\"댓글이 많음\",\"retryable\":false");

		assertThat(row(target, PublishOperation.COMMENT).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);
		assertThat(row(target, PublishOperation.COMMENT).getErrorCode()).isEqualTo("MARKER_CHECK_INCOMPLETE");
	}

	@Test
	void V5_이전에_시작한_묶음처럼_발행_기록이_없어도_결과는_반영한다() throws Exception {
		TaskItem t = confirmed();
		UUID batch = publish();
		publications.deleteAll(publications.findByTaskItem_IdOrderById(t.getId()));

		report(batch, t, PUBLISHED);

		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
		assertThat(publications.findByTaskItem_IdOrderById(t.getId())).isEmpty();
	}
}
