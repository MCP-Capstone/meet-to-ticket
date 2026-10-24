package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

// 실패한 티켓 재시도 (REQ-019, TASK-049). 테스트마다 새 사용자와 프로젝트를 만들어 서로 영향을 주지 않는다.
@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=retry-test-token",
		"spring.datasource.url=jdbc:h2:mem:publish-retry;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishRetryTest {
	private static final String TOKEN = "retry-test-token";

	@MockitoBean PublishGateway gateway;
	@Autowired MockMvc mvc;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;

	private User user;
	private MockHttpSession session;
	private Project project;
	private ProjectMember member;

	@BeforeEach
	void setUp() {
		user = newUser();
		session = session(user);
		project = projects.save(new Project("재시도 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
		when(gateway.mode()).thenReturn("LIVE");
		when(gateway.submit(any())).thenReturn(Optional.empty()); // 접수만 하고 결과는 나중에 보고된다
	}

	private User newUser() {
		return users.save(new User("rt" + UUID.randomUUID().toString().substring(0, 8), "hash", "재시도자"));
	}

	private MockHttpSession session(User u) {
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(SessionConst.LOGIN_USER_ID, u.getId());
		return s;
	}

	// 내가 담당인 티켓 하나를 주어진 상태로 만든다. 실패 상태면 실패 코드를 함께 남긴다.
	private TaskItem ticket(TicketStatus status, String errorCode) {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 23), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "재시도 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		t.moveTo(status);
		if (status == TicketStatus.FAILED)
			t.recordPublishResult(TicketStatus.FAILED, null, null, null, errorCode, "실패 사유", true, false);
		return items.save(t);
	}

	private TaskItem failed() { return ticket(TicketStatus.FAILED, "PUBLISH_FAILED"); }

	private TaskItem commentFailed() {
		TaskItem t = ticket(TicketStatus.UPDATED, null);
		t.recordPublishResult(TicketStatus.UPDATED, ProcessingType.UPDATE, 73,
				"https://github.com/owner/repo/issues/73", "COMMENT_FAILED", "댓글 오류", true, false);
		return items.save(t);
	}

	private UUID retryComment(TaskItem t) throws Exception {
		String body = mvc.perform(post("/api/tickets/{id}/retry-comment", t.getId()).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.read(body).path("batchId").asText());
	}

	@Test
	void 재시도_불가_실패는_직접_API를_호출해도_차단한다() throws Exception {
		TaskItem t = failed();
		t.recordPublishResult(TicketStatus.FAILED, null, null, null, "GITHUB_AUTH", "권한 오류", false, false);
		items.save(t);
		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLISH_RETRY_NOT_ALLOWED"));
		verify(gateway, never()).submit(any());
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		// 안전한 수정/재승인 경로는 유지한다.
		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
	}

	@Test
	void 댓글만_재전송하고_실제_갱신한_이슈_번호를_전달한다() throws Exception {
		TaskItem t = commentFailed();
		// 저장된 초안의 dedupe=null이어도 CREATE로 재발행하면 안 된다.
		UUID batch = retryComment(t);
		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		var sent = captor.getValue().tickets().getFirst();
		assertThat(sent.retryCommentOnly()).isTrue();
		assertThat(sent.processingType()).isEqualTo("UPDATE");
		assertThat(sent.targetIssueNumber()).isEqualTo(73);
		mvc.perform(post("/api/tickets/{id}/retry-comment", t.getId()).session(session)).andExpect(status().isConflict());
		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","items":[{"ticketId":"%s","processingType":"UPDATE","status":"UPDATED"}]}""".formatted(batch, t.getId()));
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.UPDATED);
		assertThat(reload(t).getPublishErrorCode()).isNull();
		assertThat(reload(t).getGithubIssueNumber()).isEqualTo(73);
		assertThat(reload(t).getGithubIssueUrl()).endsWith("/73");
	}

	@Test
	void 댓글_재전송_실패나_보고_유실에도_본문_갱신_성공을_유지한다() throws Exception {
		for (String result : List.of("\"items\":[]", "\"items\":[{\"ticketId\":\"%s\",\"status\":\"UNKNOWN\",\"errorMessage\":\"연결 끊김\"}]")) {
			TaskItem t = commentFailed();
			UUID batch = retryComment(t);
			report(batch, "{\"batchId\":\"%s\",\"status\":\"FAILED\",%s}".formatted(batch, result.formatted(t.getId())));
			TaskItem kept = reload(t);
			assertThat(kept.getTicketStatus()).isEqualTo(TicketStatus.UPDATED);
			assertThat(kept.getPublishErrorCode()).isEqualTo("COMMENT_FAILED");
			assertThat(kept.getGithubIssueNumber()).isEqualTo(73);
			retryComment(kept);
		}
	}

	@Test
	void 댓글_접수_실패하면_UPDATED로_복원한다() throws Exception {
		TaskItem t = commentFailed();
		when(gateway.submit(any())).thenThrow(new AgentException(false));
		mvc.perform(post("/api/tickets/{id}/retry-comment", t.getId()).session(session)).andExpect(status().isBadGateway());
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.UPDATED);
		assertThat(reload(t).getPublishErrorCode()).isEqualTo("COMMENT_FAILED");
	}

	@Test
	void 댓글_재전송도_담당자와_상태를_검증한다() throws Exception {
		TaskItem t = commentFailed();
		mvc.perform(post("/api/tickets/{id}/retry-comment", t.getId()).session(session(newUser()))).andExpect(status().isForbidden());
		mvc.perform(post("/api/tickets/{id}/retry-comment", t.getId())).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/tickets/{id}/retry-comment", UUID.randomUUID()).session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/api/tickets/{id}/retry-comment", failed().getId()).session(session)).andExpect(status().isConflict());
		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session)).andExpect(status().isConflict());
		verify(gateway, never()).submit(any());
		mvc.perform(get("/api/tickets/me").session(session))
				.andExpect(jsonPath("$.tickets[?(@.ticketId == '%s')].publishFailure.errorCode".formatted(t.getId())).value(org.hamcrest.Matchers.hasItem("COMMENT_FAILED")));
	}

	@Test
	void 댓글_재전송_동시_요청도_한번만_보낸다() throws Exception {
		TaskItem t = commentFailed();
		var pool = Executors.newFixedThreadPool(4);
		try {
			Callable<Integer> call = () -> mvc.perform(post("/api/tickets/{id}/retry-comment", t.getId()).session(session)).andReturn().getResponse().getStatus();
			var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(call)).toList();
			List<Integer> statuses = new java.util.ArrayList<>();
			for (var f : futures) statuses.add(f.get());
			assertThat(statuses).containsOnlyOnce(202).filteredOn(s -> s != 202).containsOnly(409);
			verify(gateway, times(1)).submit(any());
		} finally { pool.shutdown(); }
	}

	private TaskItem reload(TaskItem t) { return items.findById(t.getId()).orElseThrow(); }

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

	@Test
	void 재시도가_성공하면_발행됨으로_저장한다() throws Exception {
		TaskItem t = failed();

		UUID batch = retry(t);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets()).extracting(PublishGateway.Ticket::ticketId).containsExactly(t.getId().toString());
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);

		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","items":[
				 {"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED","githubIssueNumber":7,
				  "githubIssueUrl":"https://github.com/owner/repo/issues/7","retryable":false}]}"""
				.formatted(batch, t.getId()));

		var done = reload(t);
		assertThat(done.getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
		assertThat(done.getGithubIssueNumber()).isEqualTo(7);
		assertThat(done.getPublishErrorCode()).isNull();
		assertThat(done.getErrorMessage()).isNull();
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.summary.published").value(1))
				.andExpect(jsonPath("$.tickets[0].failureCategory").doesNotExist());
	}

	@Test
	void 재시도가_연결이나_갱신으로_끝나면_그_상태로_저장한다() throws Exception {
		TaskItem linked = failed();
		UUID b1 = retry(linked);
		report(b1, """
				{"batchId":"%s","status":"SUCCEEDED","items":[{"ticketId":"%s","processingType":"LINK","status":"LINKED","githubIssueNumber":3,
				 "githubIssueUrl":"https://github.com/owner/repo/issues/3","retryable":false}]}""".formatted(b1, linked.getId()));
		TaskItem updated = failed();
		UUID b2 = retry(updated);
		report(b2, """
				{"batchId":"%s","status":"SUCCEEDED","items":[{"ticketId":"%s","processingType":"UPDATE","status":"UPDATED","githubIssueNumber":4,
				 "githubIssueUrl":"https://github.com/owner/repo/issues/4","retryable":false}]}""".formatted(b2, updated.getId()));

		assertThat(reload(linked).getTicketStatus()).isEqualTo(TicketStatus.LINKED);
		assertThat(reload(updated).getTicketStatus()).isEqualTo(TicketStatus.UPDATED);
	}

	@Test
	void 다시_실패하면_실패로_남고_새_사유로_바뀐다() throws Exception {
		TaskItem t = failed();
		UUID batch = retry(t);

		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"GITHUB_RATE_LIMIT","errorMessage":"잠시 후 다시 시도하세요.","retryable":true}]}""".formatted(batch, t.getId()));

		var again = reload(t);
		assertThat(again.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(again.getPublishErrorCode()).isEqualTo("GITHUB_RATE_LIMIT");
		// 다시 실패해도 또 다시 시도할 수 있다
		retry(again);
	}

	@Test
	void 실패가_아닌_티켓은_409이고_Agent로_보내지_않는다() throws Exception {
		for (TicketStatus status : List.of(TicketStatus.CONFIRMED, TicketStatus.PENDING_REVIEW, TicketStatus.PUBLISHED, TicketStatus.PUBLISHING)) {
			TaskItem t = ticket(status, null);
			mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
			assertThat(reload(t).getTicketStatus()).isEqualTo(status);
		}
		verify(gateway, never()).submit(any());
	}

	@Test
	void 담당자가_아니면_403이고_없는_티켓은_404다() throws Exception {
		TaskItem t = failed();
		User other = newUser();

		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session(other)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TICKET_ASSIGNEE_REQUIRED"));
		mvc.perform(post("/api/tickets/{id}/retry", UUID.randomUUID()).session(session))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
		mvc.perform(post("/api/tickets/{id}/retry", t.getId())).andExpect(status().isUnauthorized());
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		verify(gateway, never()).submit(any());
	}

	@Test
	void 같은_티켓을_동시에_재시도해도_한_번만_보낸다() throws Exception {
		TaskItem t = failed();
		var pool = Executors.newFixedThreadPool(4);
		try {
			Callable<Integer> call = () -> mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
					.andReturn().getResponse().getStatus();
			var futures = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(call)).toList();
			List<Integer> statuses = new java.util.ArrayList<>();
			for (var f : futures) statuses.add(f.get());

			assertThat(statuses).containsOnlyOnce(202).filteredOn(s -> s != 202).containsOnly(409);
			verify(gateway, times(1)).submit(any());
		} finally {
			pool.shutdown();
		}
	}

	@Test
	void 저장소_권한_문제는_실패_분류로_구분한다() throws Exception {
		TaskItem permission = ticket(TicketStatus.FAILED, "GITHUB_FORBIDDEN");
		TaskItem other = ticket(TicketStatus.FAILED, "PUBLISH_FAILED");
		TaskItem notAllowed = ticket(TicketStatus.FAILED, "REPOSITORY_NOT_ALLOWED");

		// 재시도 결과로 권한 문제가 다시 나온 경우를 묶음 결과에서 확인한다
		UUID batch = retry(permission);
		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"GITHUB_FORBIDDEN","errorMessage":"저장소 권한이 없습니다.","retryable":false}]}""".formatted(batch, permission.getId()));
		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.tickets[0].errorCode").value("GITHUB_FORBIDDEN"))
				.andExpect(jsonPath("$.tickets[0].failureCategory").value("REPOSITORY_PERMISSION"));

		UUID b2 = retry(other);
		report(b2, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"PUBLISH_FAILED","errorMessage":"실패","retryable":true}]}""".formatted(b2, other.getId()));
		mvc.perform(get("/api/publish-batches/{id}", b2).session(session))
				.andExpect(jsonPath("$.tickets[0].failureCategory").value("OTHER"));

		assertThat(PublishFailureCategory.of("REPOSITORY_NOT_ALLOWED")).isEqualTo(PublishFailureCategory.REPOSITORY_PERMISSION);
		assertThat(PublishFailureCategory.of("GITHUB_AUTH")).isEqualTo(PublishFailureCategory.REPOSITORY_PERMISSION);
		assertThat(PublishFailureCategory.of(null)).isNull();
		assertThat(reload(notAllowed).getPublishErrorCode()).isEqualTo("REPOSITORY_NOT_ALLOWED");
	}

	@Test
	void Agent가_접수하지_않으면_실패_상태와_사유를_그대로_둔다() throws Exception {
		TaskItem t = ticket(TicketStatus.FAILED, "GITHUB_FORBIDDEN");
		when(gateway.submit(any())).thenThrow(new AgentException(false));

		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
				.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("AGENT_ACCEPTANCE_FAILED"));

		var kept = reload(t);
		assertThat(kept.getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(kept.getPublishErrorCode()).isEqualTo("GITHUB_FORBIDDEN");
		// 접수 실패 뒤에 다시 시도할 수 있다
		doReturn(Optional.empty()).when(gateway).submit(any());
		retry(kept);
	}
}
