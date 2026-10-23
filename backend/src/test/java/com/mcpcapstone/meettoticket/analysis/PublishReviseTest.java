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

// 실패 티켓 수정하러 되돌리기와 결과 모름 재발행 표시 (#211). 테스트마다 새 사용자와 프로젝트를 만들어 서로 영향을 주지 않는다.
@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=revise-test-token",
		"spring.datasource.url=jdbc:h2:mem:publish-revise;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishReviseTest {
	private static final String TOKEN = "revise-test-token";

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
		project = projects.save(new Project("수정 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
		when(gateway.mode()).thenReturn("LIVE");
		when(gateway.submit(any())).thenReturn(Optional.empty()); // 접수만 하고 결과는 나중에 보고된다
	}

	private User newUser() {
		return users.save(new User("rv" + UUID.randomUUID().toString().substring(0, 8), "hash", "재시도자"));
	}

	private MockHttpSession session(User u) {
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(SessionConst.LOGIN_USER_ID, u.getId());
		return s;
	}

	// 내가 담당인 티켓 하나를 주어진 상태로 만든다. 실패 상태면 실패 코드를 함께 남긴다.
	private TaskItem ticket(TicketStatus status, String errorCode) { return ticket(status, errorCode, false); }

	private TaskItem ticket(TicketStatus status, String errorCode, boolean unknown) {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 23), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "재시도 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		t.moveTo(status);
		if (status == TicketStatus.FAILED)
			t.recordPublishResult(TicketStatus.FAILED, null, 5, "https://github.com/owner/repo/issues/5", errorCode, "실패 사유", true, unknown);
		return items.save(t);
	}

	private TaskItem failed() { return ticket(TicketStatus.FAILED, "PUBLISH_FAILED"); }

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

	private TaskItem unknown() { return ticket(TicketStatus.FAILED, "RESULT_UNKNOWN", true); }

	@Test
	void 실패_티켓을_수정으로_되돌리면_검토_대기가_되고_지난_결과가_비워진다() throws Exception {
		TaskItem t = failed();

		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

		var kept = reload(t);
		assertThat(kept.getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(kept.getPublishErrorCode()).isNull();
		assertThat(kept.getErrorMessage()).isNull();
		assertThat(kept.isPublishRetryable()).isFalse();
		assertThat(kept.isPublishOutcomeUnknown()).isFalse();
		assertThat(kept.getPublishBatch()).isNull();
		assertThat(kept.getGithubIssueNumber()).isNull();
		assertThat(kept.mustRecheckOnPublish()).isFalse();
		// 고친 뒤 다시 확인할 수 있다
		mvc.perform(post("/api/tickets/{id}/confirm", t.getId()).session(session))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
	}

	@Test
	void 실패가_아닌_티켓의_수정_되돌리기는_409다() throws Exception {
		for (TicketStatus status : List.of(TicketStatus.CONFIRMED, TicketStatus.PENDING_REVIEW, TicketStatus.REJECTED, TicketStatus.PUBLISHED, TicketStatus.PUBLISHING)) {
			TaskItem t = ticket(status, null);
			mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
			assertThat(reload(t).getTicketStatus()).isEqualTo(status);
		}
	}

	@Test
	void 담당자가_아니면_403이고_없는_티켓은_404다() throws Exception {
		TaskItem t = failed();
		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session(newUser())))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TICKET_ASSIGNEE_REQUIRED"));
		mvc.perform(post("/api/tickets/{id}/revise", UUID.randomUUID()).session(session))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/tickets/{id}/revise", t.getId())).andExpect(status().isUnauthorized());
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.FAILED);
	}

	@Test
	void 지난_묶음의_결과_조회는_티켓을_수정으로_되돌려도_그대로다() throws Exception {
		TaskItem t = failed();
		UUID batch = retry(t);
		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"GITHUB_RATE_LIMIT","errorMessage":"잠시 후 다시 시도하세요.","retryable":true}]}""".formatted(batch, t.getId()));

		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session)).andExpect(status().isOk());

		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.summary.total").value(1))
				.andExpect(jsonPath("$.summary.failed").value(1))
				.andExpect(jsonPath("$.tickets[0].status").value("FAILED"))
				.andExpect(jsonPath("$.tickets[0].errorCode").value("GITHUB_RATE_LIMIT"));
	}

	@Test
	void 다시_시도해_새_묶음으로_옮겨져도_지난_묶음의_결과가_남는다() throws Exception {
		TaskItem t = failed();
		UUID first = retry(t);
		report(first, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"GITHUB_ERROR","errorMessage":"실패","retryable":true}]}""".formatted(first, t.getId()));
		UUID second = retry(t);
		report(second, """
				{"batchId":"%s","status":"SUCCEEDED","items":[{"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED",
				 "githubIssueNumber":9,"githubIssueUrl":"https://github.com/owner/repo/issues/9","retryable":false}]}""".formatted(second, t.getId()));

		mvc.perform(get("/api/publish-batches/{id}", first).session(session))
				.andExpect(jsonPath("$.tickets[0].status").value("FAILED"))
				.andExpect(jsonPath("$.tickets[0].errorCode").value("GITHUB_ERROR"));
		mvc.perform(get("/api/publish-batches/{id}", second).session(session))
				.andExpect(jsonPath("$.tickets[0].status").value("PUBLISHED"));
	}

	@Test
	void 결과_모름_티켓은_수정을_거쳐도_다시_발행할_때_재대조_표시를_보낸다() throws Exception {
		TaskItem t = unknown();
		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session)).andExpect(status().isOk());
		assertThat(reload(t).isPublishOutcomeUnknown()).isFalse();
		assertThat(reload(t).mustRecheckOnPublish()).isTrue();
		mvc.perform(post("/api/tickets/{id}/confirm", t.getId()).session(session)).andExpect(status().isOk());

		mvc.perform(post("/api/tickets/publish").session(session)).andExpect(status().isAccepted());

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets()).hasSize(1);
		assertThat(captor.getValue().tickets().get(0).previousOutcomeUnknown()).isTrue();
	}

	@Test
	void 결과_모름_티켓을_바로_다시_시도해도_재대조_표시를_보낸다() throws Exception {
		TaskItem t = unknown();

		retry(t);

		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets().get(0).previousOutcomeUnknown()).isTrue();
	}

	@Test
	void 결과_모름이_아니었던_티켓은_표시를_보내지_않고_확실한_성공이면_표시가_풀린다() throws Exception {
		TaskItem plain = failed();
		retry(plain);
		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets().get(0).previousOutcomeUnknown()).isFalse();

		TaskItem t = unknown();
		UUID batch = retry(t);
		report(batch, """
				{"batchId":"%s","status":"SUCCEEDED","items":[{"ticketId":"%s","processingType":"CREATE","status":"PUBLISHED",
				 "githubIssueNumber":5,"githubIssueUrl":"https://github.com/owner/repo/issues/5","retryable":false}]}""".formatted(batch, t.getId()));
		assertThat(reload(t).mustRecheckOnPublish()).isFalse();
	}

	@Test
	void 다시_결과_모름이_아닌_실패가_와도_지난_결과_모름은_이어진다() throws Exception {
		TaskItem t = unknown();
		UUID batch = retry(t);
		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"GITHUB_RATE_LIMIT","errorMessage":"실패","retryable":true}]}""".formatted(batch, t.getId()));

		var again = reload(t);
		assertThat(again.isPublishOutcomeUnknown()).isFalse();
		assertThat(again.mustRecheckOnPublish()).isTrue();
	}
}
