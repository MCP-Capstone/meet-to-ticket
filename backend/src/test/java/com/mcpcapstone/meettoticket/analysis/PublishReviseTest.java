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

	private TaskItem ticket(TicketStatus status, String errorCode, boolean unknown) { return ticket(status, errorCode, unknown, PublishTarget.GITHUB); }

	private TaskItem ticket(TicketStatus status, String errorCode, boolean unknown, PublishTarget target) {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 23), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "재시도 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		t.changePublication(target, ProcessingType.CREATE);
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

	private org.springframework.test.web.servlet.ResultActions resolve(TaskItem t, String body) throws Exception {
		return mvc.perform(post("/api/tickets/{id}/resolve-outcome", t.getId()).session(session)
				.contentType("application/json").content(body));
	}

	// 재대조할 수 없는 대상(Notion, Slack)의 결과 모름 실패
	private TaskItem unknownOn(PublishTarget target) { return ticket(TicketStatus.FAILED, "RESULT_UNKNOWN", true, target); }

	@Test
	void 확인_결과가_필요한_실패는_다시_시도할_수_없고_미반영_확인_뒤에는_차단_표시_없이_보낸다() throws Exception {
		TaskItem t = unknownOn(PublishTarget.SLACK);
		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
		verify(gateway, never()).submit(any());

		// 버린 url은 검사하지 않는다
		resolve(t, "{\"applied\":false,\"url\":\"notion.so/x\"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"))
				.andExpect(jsonPath("$.publishFailure.errorCode").value("VERIFIED_NOT_APPLIED"))
				.andExpect(jsonPath("$.publishFailure.retryable").value(true))
				.andExpect(jsonPath("$.publishFailure.outcomeUnknown").value(false))
				.andExpect(jsonPath("$.publishFailure.resolution").value("RETRY"));
		assertThat(reload(t).getGithubIssueUrl()).isNull();

		clearInvocations(gateway);
		retry(t);
		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway).submit(captor.capture());
		assertThat(captor.getValue().tickets().get(0).previousOutcomeUnknown()).isFalse();
	}

	@Test
	void 반영됨_확인은_발행됨과_대상_링크로_끝내고_지난_이슈_번호를_남기지_않는다() throws Exception {
		TaskItem t = unknownOn(PublishTarget.NOTION);

		resolve(t, "{\"applied\":true,\"url\":\" https://www.notion.so/page-1 \"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PUBLISHED"))
				.andExpect(jsonPath("$.publishFailure").doesNotExist());

		var done = reload(t);
		assertThat(done.getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
		assertThat(done.getGithubIssueUrl()).isEqualTo("https://www.notion.so/page-1");
		assertThat(done.getGithubIssueNumber()).isNull();
		assertThat(done.getPublishBatch()).isNull();
		assertThat(done.getPublishErrorCode()).isNull();
		assertThat(done.mustRecheckOnPublish()).isFalse();
	}

	@Test
	void 확인_결과는_재대조할_수_없는_대상의_결과_모름_실패만_받는다() throws Exception {
		// GitHub 결과 모름은 다시 시도하면 Agent가 재대조하므로 받지 않는다
		TaskItem github = unknown();
		resolve(github, "{\"applied\":false}").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
		assertThat(reload(github).mustRecheckOnPublish()).isTrue();

		resolve(ticket(TicketStatus.FAILED, "NOTION_ROOT_MISSING", false, PublishTarget.NOTION), "{\"applied\":false}").andExpect(status().isConflict());
		resolve(ticket(TicketStatus.CONFIRMED, null), "{\"applied\":true}").andExpect(status().isConflict());
	}

	@Test
	void 실제_Notion_app_도메인_링크를_저장하고_카드에_돌려준다() throws Exception {
		TaskItem t = unknownOn(PublishTarget.NOTION);
		resolve(t, "{\"applied\":true,\"url\":\"https://app.notion.com/p/page-1\"}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.githubIssueUrl").value("https://app.notion.com/p/page-1"));
	}

	@Test
	void 반영됨_링크는_그_대상의_https_링크만_받고_잘못된_입력은_400이다() throws Exception {
		TaskItem notion = unknownOn(PublishTarget.NOTION);
		resolve(notion, "{}").andExpect(status().isBadRequest());
		for (String url : List.of("javascript:alert(1)", "http://www.notion.so/p", "https://hooks.slack.com/x", "https://notion.so.evil.com/p",
				"https://notion.site/p", "https://user@www.notion.so/p", "https://www.notion.so:8443/p"))
			resolve(notion, "{\"applied\":true,\"url\":\"%s\"}".formatted(url)).andExpect(status().isBadRequest());
		mvc.perform(post("/api/tickets/{id}/resolve-outcome", notion.getId()).session(session(newUser()))
						.contentType("application/json").content("{\"applied\":true}"))
				.andExpect(status().isForbidden());
		assertThat(reload(notion).getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(reload(notion).mustRecheckOnPublish()).isTrue();

		TaskItem slack = unknownOn(PublishTarget.SLACK);
		resolve(slack, "{\"applied\":true,\"url\":\"https://team.slack.com/archives/C1/p1\"}").andExpect(status().isOk());
		assertThat(reload(slack).getGithubIssueUrl()).isEqualTo("https://team.slack.com/archives/C1/p1");
	}

	private org.springframework.test.web.servlet.ResultActions changeTarget(TaskItem t, String target) throws Exception {
		return mvc.perform(patch("/api/tickets/{id}/publication", t.getId()).session(session)
				.contentType("application/json").content("{\"publishTarget\":\"%s\"}".formatted(target)));
	}

	@Test
	void 결과_모름이_남은_티켓은_수정으로_되돌려도_발행_대상을_바꿀_수_없다() throws Exception {
		TaskItem t = unknown(); // GitHub 결과 모름
		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session))
				.andExpect(status().isOk()).andExpect(jsonPath("$.publishTargetLocked").value(true));

		changeTarget(t, "NOTION").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLISH_TARGET_LOCKED"));
		assertThat(reload(t).getPublishTarget()).isEqualTo(PublishTarget.GITHUB);
		// 같은 대상이면 막지 않는다
		changeTarget(t, "GITHUB").andExpect(status().isOk());
	}

	@Test
	void 확인_결과가_필요한_실패는_수정으로_되돌릴_수_없다() throws Exception {
		TaskItem t = unknownOn(PublishTarget.SLACK);
		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
		assertThat(reload(t).getTicketStatus()).isEqualTo(TicketStatus.FAILED);
	}

	@Test
	void 확실한_실패나_미반영_확인_뒤에는_발행_대상을_바꿀_수_있다() throws Exception {
		TaskItem plain = failed();
		mvc.perform(post("/api/tickets/{id}/revise", plain.getId()).session(session))
				.andExpect(status().isOk()).andExpect(jsonPath("$.publishTargetLocked").value(false));
		changeTarget(plain, "NOTION").andExpect(status().isOk());

		TaskItem t = unknownOn(PublishTarget.NOTION);
		resolve(t, "{\"applied\":false}").andExpect(status().isOk());
		mvc.perform(post("/api/tickets/{id}/revise", t.getId()).session(session)).andExpect(status().isOk());
		changeTarget(t, "GITHUB").andExpect(status().isOk());
	}

	@Test
	void 미반영_확인은_결과_모름_뒤의_설정_오류_사유를_덮지_않는다() throws Exception {
		TaskItem t = unknownOn(PublishTarget.NOTION);
		// 결과 모름 뒤 재발행이 설정 오류로 끝난 상태
		t.recordPublishResult(TicketStatus.FAILED, null, null, null, "NOTION_ROOT_MISSING", "Notion 기획 페이지 URL이 없습니다", false, false);
		items.save(t);
		assertThat(reload(t).mustRecheckOnPublish()).isTrue();

		resolve(t, "{\"applied\":false}").andExpect(status().isOk())
				.andExpect(jsonPath("$.publishFailure.errorCode").value("NOTION_ROOT_MISSING"))
				.andExpect(jsonPath("$.publishFailure.outcomeUnknown").value(false));
	}

	@Test
	void 결과_모름_실패는_확인하러_갈_곳을_알려_준다() throws Exception {
		project.update(project.getName(), project.getDescription(), project.getGithubOwner(), project.getGithubRepo(), "https://www.notion.so/plan", "C123");
		projects.save(project);
		TaskItem github = unknown();
		TaskItem notion = unknownOn(PublishTarget.NOTION);
		TaskItem slack = unknownOn(PublishTarget.SLACK);
		TaskItem plain = failed();

		var body = json.read(mvc.perform(get("/api/tickets/me").session(session)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		var urls = new java.util.HashMap<String, String>();
		for (var node : body.path("tickets"))
			urls.put(node.path("ticketId").asText(), node.path("publishFailure").path("checkUrl").asText(null));
		assertThat(urls.get(github.getId().toString())).isEqualTo("https://github.com/owner/" + project.getGithubRepo() + "/issues");
		assertThat(urls.get(notion.getId().toString())).isEqualTo("https://www.notion.so/plan");
		assertThat(urls.get(slack.getId().toString())).isEqualTo("https://slack.com/app_redirect?channel=C123");
		assertThat(urls.get(plain.getId().toString())).isNull();
	}

	@Test
	void 발행_결과의_결과_모름은_차단된_재발행에도_표시된다() throws Exception {
		// 이 변경 전에 수정으로 되돌려 다시 확인해 둔 Notion 결과 모름 티켓 (지금은 수정이 막힌다)
		TaskItem t = unknownOn(PublishTarget.NOTION);
		t.reviseFailed();
		t.moveTo(TicketStatus.CONFIRMED);
		items.save(t);
		UUID batch = UUID.fromString(json.read(mvc.perform(post("/api/tickets/publish").session(session)).andExpect(status().isAccepted())
				.andReturn().getResponse().getContentAsString()).path("batchId").asText());
		report(batch, """
				{"batchId":"%s","status":"FAILED","items":[{"ticketId":"%s","processingType":"CREATE","status":"FAILED",
				 "errorCode":"NOTION_VERIFY_REQUIRED","errorMessage":"확인 필요","retryable":false}]}""".formatted(batch, t.getId()));

		mvc.perform(get("/api/publish-batches/{id}", batch).session(session))
				.andExpect(jsonPath("$.tickets[0].errorCode").value("NOTION_VERIFY_REQUIRED"))
				.andExpect(jsonPath("$.tickets[0].outcomeUnknown").value(true));
	}

	@Test
	void 내_티켓_조회는_대상에_따라_실패를_푸는_방법을_알려_준다() throws Exception {
		TaskItem github = unknown();
		TaskItem notion = unknownOn(PublishTarget.NOTION);
		TaskItem plainNotion = ticket(TicketStatus.FAILED, "NOTION_ROOT_MISSING", false, PublishTarget.NOTION);

		var body = json.read(mvc.perform(get("/api/tickets/me").session(session)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		var resolutions = new java.util.HashMap<String, String>();
		for (var node : body.path("tickets"))
			resolutions.put(node.path("ticketId").asText(), node.path("publishFailure").path("resolution").asText());
		assertThat(resolutions.get(github.getId().toString())).isEqualTo("RETRY");
		assertThat(resolutions.get(notion.getId().toString())).isEqualTo("MANUAL_CHECK");
		assertThat(resolutions.get(plainNotion.getId().toString())).isEqualTo("RETRY");
	}

	@Test
	void 내_티켓_조회는_실패_티켓에만_실패_사유를_담는다() throws Exception {
		TaskItem t = unknown();
		TaskItem ok = ticket(TicketStatus.CONFIRMED, null);

		var body = json.read(mvc.perform(get("/api/tickets/me").session(session)).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());

		assertThat(body.path("counts").path("failed").asInt()).isEqualTo(1);
		for (var node : body.path("tickets")) {
			if (node.path("ticketId").asText().equals(t.getId().toString())) {
				assertThat(node.path("publishFailure").path("errorCode").asText()).isEqualTo("RESULT_UNKNOWN");
				assertThat(node.path("publishFailure").path("outcomeUnknown").asBoolean()).isTrue();
				assertThat(node.path("publishFailure").path("failureCategory").asText()).isEqualTo("OTHER");
			} else if (node.path("ticketId").asText().equals(ok.getId().toString())) {
				assertThat(node.path("publishFailure").isNull()).isTrue();
			}
		}
	}
}
