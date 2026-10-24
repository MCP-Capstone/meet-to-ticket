package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// GitHub 신규 이슈의 결과 모름 재시도는 마지막 시도 뒤 기다리는 시간이 지나야 보낸다 (#336).
// 다른 테스트는 기다리는 시간을 끄고(application-test.yml) 이 테스트만 켠다.
@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=retry-delay-token",
		"spring.datasource.url=jdbc:h2:mem:publish-retry-delay;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false",
		"app.publish.github-recheck-delay=3m"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublishRetryDelayTest {
	private static final String TOKEN = "retry-delay-token";

	@MockitoBean PublishGateway gateway;
	@Autowired MockMvc mvc;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired PublicationLedger ledger;
	@Autowired PublicationRepository publications;
	@Autowired TransactionTemplate tx;
	@Autowired JdbcTemplate jdbc;
	@Autowired JsonCodec json;

	private User user;
	private MockHttpSession session;
	private ProjectMember member;
	private Project project;

	@BeforeEach
	void setUp() {
		user = users.save(new User("rd" + UUID.randomUUID().toString().substring(0, 8), "hash", "재시도 대기"));
		session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		project = projects.save(new Project("재시도 대기 테스트", "owner", "repo-" + UUID.randomUUID().toString().substring(0, 8)));
		member = members.save(new ProjectMember(project, user, user.getName(), "gh-user"));
		when(gateway.mode()).thenReturn("LIVE");
		when(gateway.submit(any())).thenReturn(Optional.empty());
	}

	private TaskItem failed() {
		Meeting meeting = meetings.save(new Meeting(project, user, "회의 " + UUID.randomUUID(), LocalDate.of(2026, 10, 24), "[]", "t.txt", "원문"));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		String content = "{\"description\":\"본문\",\"dedupe\":null}";
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "대기 티켓", json.read(content), "CREATE", "사유", "APPROVED", "SKIPPED", null, null, null);
		TaskItem t = new TaskItem(task, item, content);
		t.assignTo(member);
		t.moveTo(TicketStatus.FAILED);
		t.recordPublishResult(TicketStatus.FAILED, null, null, null, "PUBLISH_FAILED", "실패 사유", true, false);
		return items.save(t);
	}

	private UUID retry(TaskItem t) throws Exception {
		String body = mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(json.read(body).path("batchId").asText());
	}

	private Publication row(TaskItem t, PublishOperation operation) {
		return publications.findByTargetAndIdempotencyKey(PublishTarget.GITHUB, Publication.keyOf(t.getId(), PublishTarget.GITHUB, operation)).orElseThrow();
	}

	private PublicationLedger.Reservation reserve(TaskItem t, PublishOperation operation) {
		return tx.execute(s -> ledger.reserve(t.getId(), PublishTarget.GITHUB, operation, UUID.randomUUID()));
	}

	// 마지막 시도를 minutes분 전으로 옮긴다
	private void age(TaskItem t, int minutes) {
		OffsetDateTime past = OffsetDateTime.now().minusMinutes(minutes);
		jdbc.update("update publications set reserved_at = ?, sent_at = ? where task_item_id = ?", past, past, t.getId());
	}

	@Test
	void GitHub_신규_이슈_결과_모름은_기다리는_시간이_지나야_다시_보낸다() throws Exception {
		TaskItem t = failed();
		UUID batch = retry(t);
		mvc.perform(post("/internal/publish-batches/{id}/result", batch).header("X-Internal-Token", TOKEN).contentType("application/json")
						.content("{\"batchId\":\"%s\",\"status\":\"FAILED\",\"items\":[{\"ticketId\":\"%s\",\"status\":\"UNKNOWN\",\"errorMessage\":\"연결 끊김\"}]}"
								.formatted(batch, t.getId())))
				.andExpect(status().isAccepted());
		assertThat(row(t, PublishOperation.CREATE).getStatus()).isEqualTo(PublicationStatus.UNCERTAIN);

		// 바로 다시 시도하면 Agent에 보내지 않고 429로 안내한다. 티켓과 기록은 그대로다.
		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PUBLISH_RETRY_TOO_SOON"));
		verify(gateway, times(1)).submit(any());
		assertThat(items.findById(t.getId()).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.FAILED);
		assertThat(row(t, PublishOperation.CREATE).getAttemptCount()).isEqualTo(1);

		age(t, 2);
		mvc.perform(post("/api/tickets/{id}/retry", t.getId()).session(session)).andExpect(status().isTooManyRequests());

		age(t, 4);
		retry(t);
		var captor = ArgumentCaptor.forClass(PublishGateway.Request.class);
		verify(gateway, times(2)).submit(captor.capture());
		assertThat(captor.getValue().tickets().getFirst().previousOutcomeUnknown()).isTrue();
		assertThat(row(t, PublishOperation.CREATE).getAttemptCount()).isEqualTo(2);
	}

	@Test
	void 확정_실패와_이슈_번호로_확인하는_연결은_기다리지_않는다() {
		// 반영되지 않은 것이 확실한 실패는 이전 이슈가 없으므로 바로 다시 보낸다.
		TaskItem failedOnce = failed();
		assertThat(reserve(failedOnce, PublishOperation.CREATE)).isEqualTo(PublicationLedger.Reservation.RESERVED);
		Long failedId = row(failedOnce, PublishOperation.CREATE).getId();
		tx.executeWithoutResult(s -> publications.finish(failedId, PublicationStatus.FAILED, List.of(PublicationStatus.RESERVED), "GITHUB_VALIDATION", null, null, OffsetDateTime.now()));
		assertThat(reserve(failedOnce, PublishOperation.CREATE)).isEqualTo(PublicationLedger.Reservation.RESERVED);

		// LINK, UPDATE는 Agent가 이슈 번호로 댓글/본문을 직접 조회해 검색 색인 지연이 없다.
		TaskItem link = failed();
		assertThat(reserve(link, PublishOperation.LINK)).isEqualTo(PublicationLedger.Reservation.RESERVED);
		Long linkId = row(link, PublishOperation.LINK).getId();
		tx.executeWithoutResult(s -> publications.finish(linkId, PublicationStatus.UNCERTAIN, List.of(PublicationStatus.RESERVED), "RESULT_LOST", null, null, OffsetDateTime.now()));
		assertThat(reserve(link, PublishOperation.LINK)).isEqualTo(PublicationLedger.Reservation.RESERVED);
	}
}
