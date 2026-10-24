package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
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

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 담당자별 티켓 저장(REQ-010)과 내 티켓 조회(REQ-013). 데모 로그인 사용자(함주헌)가 담당인지로 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MyTicketTest {
	@MockitoBean AnalysisTaskPublisher publisher;
	@Autowired AnalysisTaskListener listener;
	@Autowired AnalysisCoordinator coordinator;
	@Autowired TaskItemRepository items;
	@Autowired ProjectRepository projects;
	@Autowired UserRepository users;
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;

	private MockHttpSession me;
	private Long projectId;

	@BeforeEach
	void login() throws Exception {
		me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
						.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
				.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
		projectId = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow().getId();
	}

	// 참여자를 지정해 회의를 저장하고 분석을 끝낸 뒤 만들어진 티켓 ID를 돌려준다. Mock Agent는 첫 참여자를 담당으로 쓴다.
	private UUID analyze(String participants) throws Exception {
		String text = "# 티켓 테스트\n회의 날짜: 2026-10-22\n회의 참여자: " + participants + "\n\n[구간 1] API를 내일 오후 6시까지 수정한다.\n";
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

	private int pending() throws Exception {
		String body = mvc.perform(get("/api/tickets/me").session(me)).andReturn().getResponse().getContentAsString();
		return json.read(body).path("counts").path("pendingReview").intValue();
	}

	@Test
	void 내가_담당인_티켓만_검토_대기로_보인다() throws Exception {
		int before = pending();
		UUID mine = analyze("함주헌");
		UUID others = analyze("박종민"); // 가입하지 않은 멤버(데모 데이터)가 담당이다

		assertThat(items.findById(mine).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(items.findById(mine).orElseThrow().getProcessingType()).isEqualTo(ProcessingType.CREATE);
		// 담당 멤버가 있으면 사용자가 없어도 검토 대기다
		assertThat(items.findById(others).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);

		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.counts.pendingReview").value(before + 1))
				.andExpect(jsonPath("$.counts.confirmed").value(0))
				.andExpect(jsonPath("$.counts.rejected").value(0))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + mine + "')].status").value("PENDING_REVIEW"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + mine + "')].projectId").value(projectId.intValue()))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + others + "')]").isEmpty());
	}

	@Test
	void 프로젝트로_거르고_건수도_같은_범위로_센다() throws Exception {
		analyze("함주헌");

		mvc.perform(get("/api/tickets/me").param("projectId", String.valueOf(projectId)).session(me))
				.andExpect(jsonPath("$.tickets.length()").value(greaterThanOrEqualTo(1)))
				.andExpect(jsonPath("$.tickets[?(@.projectId!=" + projectId + ")]").isEmpty());
		mvc.perform(get("/api/tickets/me").param("projectId", "999999").session(me))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tickets.length()").value(0))
				.andExpect(jsonPath("$.counts.pendingReview").value(0));
		mvc.perform(get("/api/tickets/me").param("projectId", "abc").session(me))
				.andExpect(status().isBadRequest());
	}

	@Test
	void 검토_대기_티켓의_제목을_수정한다() throws Exception {
		UUID ticketId = analyze("함주헌");

		mvc.perform(patch("/api/tickets/{id}", ticketId)
				.contentType("application/json")
				.content("{\"title\":\"CSV 빈 결과 500 오류 수정\"}")
				.session(me))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("CSV 빈 결과 500 오류 수정"));

		assertThat(items.findById(ticketId).orElseThrow().getTitle()).isEqualTo("CSV 빈 결과 500 오류 수정");
	}

	@Test
	void 다른_사용자는_내_티켓을_보지_못하고_비로그인은_401이다() throws Exception {
		UUID mine = analyze("함주헌");
		MockHttpSession other = new MockHttpSession();
		other.setAttribute(SessionConst.LOGIN_USER_ID, users.save(new User("tk" + UUID.randomUUID().toString().substring(0, 8), "hash", "다른사람")).getId());

		mvc.perform(get("/api/tickets/me").session(other))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tickets.length()").value(0))
				.andExpect(jsonPath("$.counts.pendingReview").value(0));
		mvc.perform(get("/api/tickets/me")).andExpect(status().isUnauthorized());
		assertThat(mine).isNotNull();
	}
}
