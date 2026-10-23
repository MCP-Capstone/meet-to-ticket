package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
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
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 티켓 카드 수정과 확인/거절/다시 열기/되돌리기 (REQ-014, 기획 4.2).
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketReviewTest {
	@MockitoBean AnalysisTaskPublisher publisher;
	@Autowired AnalysisTaskListener listener;
	@Autowired AnalysisCoordinator coordinator;
	@Autowired TaskItemRepository items;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired UserRepository users;
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;

	private MockHttpSession me;
	private Long projectId;
	private UUID ticket;

	@BeforeEach
	void setUp() throws Exception {
		me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
						.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
				.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
		projectId = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow().getId();
		ticket = analyze("함주헌");
	}

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

	private MockHttpSession userSession(User user) {
		MockHttpSession s = new MockHttpSession();
		s.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		return s;
	}

	private User newUser() {
		return users.save(new User("tr" + UUID.randomUUID().toString().substring(0, 8), "hash", "검토자"));
	}

	private void act(String action, int expected) throws Exception {
		mvc.perform(post("/api/tickets/{id}/{action}", ticket, action).session(me)).andExpect(status().is(expected));
	}

	@Test
	void 확인_거절_다시열기_되돌리기가_기획_표대로_전이한다() throws Exception {
		mvc.perform(post("/api/tickets/{id}/confirm", ticket).session(me))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
		mvc.perform(post("/api/tickets/{id}/reopen", ticket).session(me))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
		mvc.perform(post("/api/tickets/{id}/reject", ticket).session(me))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		mvc.perform(post("/api/tickets/{id}/restore", ticket).session(me))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
	}

	@Test
	void 허용되지_않는_전이는_409다() throws Exception {
		act("reopen", 409); // 검토 대기는 다시 열 수 없다
		act("restore", 409);
		act("confirm", 200);
		act("confirm", 409);
		act("reject", 409); // 확인한 티켓은 먼저 다시 열어야 한다
		act("restore", 409);
		act("reopen", 200);
		act("reject", 200);
		act("reopen", 409); // 거절한 티켓은 되돌리기만 된다
		act("confirm", 409);

		// 발행 이후 상태는 확인도 수정도 할 수 없다
		var item = items.findById(ticket).orElseThrow();
		item.moveTo(TicketStatus.PUBLISHED);
		items.save(item);
		act("confirm", 409);
		act("reopen", 409);
		mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json").content("{\"description\":\"수정\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
	}

	@Test
	void 담당자가_아니면_403이고_없는_티켓은_404다() throws Exception {
		MockHttpSession other = userSession(newUser());
		mvc.perform(post("/api/tickets/{id}/confirm", ticket).session(other))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TICKET_ASSIGNEE_REQUIRED"));
		mvc.perform(patch("/api/tickets/{id}", ticket).session(other).contentType("application/json").content("{\"description\":\"수정\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/tickets/{id}/confirm", UUID.randomUUID()).session(me))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
		mvc.perform(post("/api/tickets/{id}/confirm", ticket)).andExpect(status().isUnauthorized());
		assertThat(items.findById(ticket).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
	}

	@Test
	void 수정한_본문과_기한이_내_티켓_조회에_반영된다() throws Exception {
		mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json")
						.content("{\"description\":\"  고친 본문  \",\"due\":{\"text\":\"금요일까지\",\"date\":\"2026-10-30\",\"time\":\"17:30\"}}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("고친 본문"))
				.andExpect(jsonPath("$.due.date").value("2026-10-30"));

		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')].description").value("고친 본문"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')].due.text").value("금요일까지"))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')].due.time").value("17:30"));

		// 보내지 않은 항목은 그대로다
		mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json").content("{\"description\":\"한 번 더\"}"))
				.andExpect(jsonPath("$.description").value("한 번 더"))
				.andExpect(jsonPath("$.due.text").value("금요일까지"));
	}

	@Test
	void 잘못된_수정_입력은_400이다() throws Exception {
		for (String body : new String[] {"{}", "{\"description\":\"   \"}", "{\"due\":{\"time\":\"25:00\"}}"})
			mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json").content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void 담당자를_바꾸면_그_멤버의_내_티켓으로_옮겨_간다() throws Exception {
		User next = newUser();
		var project = projects.findById(projectId).orElseThrow();
		var original = items.findById(ticket).orElseThrow().getAssignee();
		var nextMember = members.save(new ProjectMember(project, next, "새 담당", null));
		try {
			moveAssignee(next, nextMember);
		} finally {
			// 데모 프로젝트의 멤버 수를 다른 테스트가 기대하므로 되돌려 둔다.
			var item = items.findById(ticket).orElseThrow();
			item.assignTo(original);
			items.save(item);
			members.delete(nextMember);
		}
	}

	private void moveAssignee(User next, ProjectMember nextMember) throws Exception {

		mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json")
						.content("{\"assigneeMemberId\":" + nextMember.getId() + "}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.name").value("새 담당"))
				.andExpect(jsonPath("$.status").value("PENDING_REVIEW"));

		mvc.perform(get("/api/tickets/me").session(userSession(next)))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')].assignee.projectMemberId").value(nextMember.getId().intValue()));
		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')]").isEmpty());
		// 이전 담당자는 더 이상 고치거나 확인할 수 없다
		act("confirm", 403);
		mvc.perform(post("/api/tickets/{id}/confirm", ticket).session(userSession(next))).andExpect(status().isOk());
	}

	@Test
	void 다른_프로젝트_멤버나_없는_멤버로는_바꿀_수_없다() throws Exception {
		var other = projects.save(new Project("다른", "o" + UUID.randomUUID().toString().substring(0, 8), "r"));
		var outsider = members.save(new ProjectMember(other, newUser(), "외부", null));
		for (long id : new long[] {outsider.getId(), 987654321L})
			mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json")
							.content("{\"assigneeMemberId\":" + id + "}"))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_MEMBER_NOT_FOUND"));
		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')].assignee.name").value("함주헌"));
	}
}
