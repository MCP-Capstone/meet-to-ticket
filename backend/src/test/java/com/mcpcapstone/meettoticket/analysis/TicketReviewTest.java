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
import org.springframework.test.web.servlet.ResultActions;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 카드 상세·수정과 확인/거절/다시 열기/되돌리기 (REQ-014, TASK-028·029). 데모 로그인 사용자(함주헌)가 담당인 티켓으로 확인한다.
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

	@BeforeEach
	void login() throws Exception {
		me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
						.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
				.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
		projectId = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow().getId();
	}

	// 참여자를 지정해 회의를 저장하고 분석을 끝낸 뒤 만들어진 티켓 ID를 돌려준다. Mock Agent는 첫 참여자를 담당으로 쓴다.
	private UUID analyze(String participants) throws Exception {
		String text = "# 카드 테스트\n회의 날짜: 2026-10-22\n회의 참여자: " + participants + "\n\n[구간 1] API를 내일 오후 6시까지 수정한다.\n";
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

	private ResultActions act(UUID ticket, String action) throws Exception {
		return mvc.perform(post("/api/tickets/{id}/{action}", ticket, action).session(me));
	}

	private ResultActions edit(UUID ticket, String body) throws Exception {
		return mvc.perform(patch("/api/tickets/{id}", ticket).session(me).contentType("application/json").content(body));
	}

	private int count(String key) throws Exception {
		String body = mvc.perform(get("/api/tickets/me").session(me)).andReturn().getResponse().getContentAsString();
		return json.read(body).path("counts").path(key).intValue();
	}

	@Test
	void 상세_조회는_카드에_보일_값을_돌려준다() throws Exception {
		UUID ticket = analyze("함주헌");

		mvc.perform(get("/api/tickets/{id}", ticket).session(me))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ticketId").value(ticket.toString()))
				.andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
				.andExpect(jsonPath("$.processingType").value("CREATE"))
				.andExpect(jsonPath("$.assignee.name").value("함주헌"))
				.andExpect(jsonPath("$.mentionedAssignees[0]").value("함주헌"))
				.andExpect(jsonPath("$.body").isNotEmpty())
				.andExpect(jsonPath("$.evidenceQuotes[0]").value("# 카드 테스트"))
				.andExpect(jsonPath("$.due.date").isEmpty())
				.andExpect(jsonPath("$.edited").value(false))
				.andExpect(jsonPath("$.projectId").value(projectId.intValue()));
	}

	@Test
	void 네_가지_전이가_기획_표대로_동작하고_건수에_반영된다() throws Exception {
		UUID ticket = analyze("함주헌");
		int pending = count("pendingReview"), confirmed = count("confirmed"), rejected = count("rejected");

		act(ticket, "confirm").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
		assertThat(count("confirmed")).isEqualTo(confirmed + 1);
		assertThat(count("pendingReview")).isEqualTo(pending - 1);

		act(ticket, "reopen").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
		act(ticket, "reject").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		assertThat(count("rejected")).isEqualTo(rejected + 1);

		act(ticket, "restore").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
		assertThat(count("pendingReview")).isEqualTo(pending);
		assertThat(items.findById(ticket).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
	}

	@Test
	void 표에_없는_전이와_검토_대기가_아닌_수정은_409다() throws Exception {
		UUID ticket = analyze("함주헌");

		act(ticket, "reopen").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATE_CONFLICT"));
		act(ticket, "restore").andExpect(status().isConflict());
		act(ticket, "confirm").andExpect(status().isOk());
		act(ticket, "confirm").andExpect(status().isConflict()); // 두 번 눌러도 한 번만 바뀐다
		act(ticket, "reject").andExpect(status().isConflict());
		act(ticket, "restore").andExpect(status().isConflict());
		edit(ticket, "{\"title\":\"확인 후 수정\"}").andExpect(status().isConflict());
		assertThat(items.findById(ticket).orElseThrow().getTitle()).isNotEqualTo("확인 후 수정");
	}

	@Test
	void 보낸_칸만_고치고_상세와_목록에_반영된다() throws Exception {
		UUID ticket = analyze("함주헌");

		edit(ticket, "{\"title\":\"  API 응답 오류 수정  \",\"body\":\"## 작업 내용\\n고친 본문\",\"due\":{\"date\":\"2026-10-25\",\"time\":\"18:00\"}}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("API 응답 오류 수정"))
				.andExpect(jsonPath("$.body").value("## 작업 내용\n고친 본문"))
				.andExpect(jsonPath("$.due.date").value("2026-10-25"))
				.andExpect(jsonPath("$.due.time").value("18:00"))
				.andExpect(jsonPath("$.due.text").isEmpty())
				.andExpect(jsonPath("$.edited").value(true))
				.andExpect(jsonPath("$.assignee.name").value("함주헌"));

		// 기한만 비우면 제목과 본문은 그대로다
		edit(ticket, "{\"due\":{\"date\":null}}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.due.date").isEmpty())
				.andExpect(jsonPath("$.title").value("API 응답 오류 수정"))
				.andExpect(jsonPath("$.body").value("## 작업 내용\n고친 본문"));
		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')].title").value("API 응답 오류 수정"));
	}

	@Test
	void 담당자를_바꾸면_새_담당자의_티켓이_된다() throws Exception {
		UUID ticket = analyze("함주헌");
		Long other = members.findByProjectIdOrderById(projectId).stream()
				.filter(m -> m.getName().equals("최윤경")).findFirst().orElseThrow().getId();

		edit(ticket, "{\"assigneeMemberId\":" + other + "}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.assignee.memberId").value(other.intValue()))
				.andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
		assertThat(items.findById(ticket).orElseThrow().getAssignee().getId()).isEqualTo(other);
		// 내 티켓에서 빠지고, 더는 내가 고칠 수 없다
		mvc.perform(get("/api/tickets/me").session(me))
				.andExpect(jsonPath("$.tickets[?(@.ticketId=='" + ticket + "')]").isEmpty());
		mvc.perform(get("/api/tickets/{id}", ticket).session(me)).andExpect(status().isForbidden());
	}

	@Test
	void 잘못된_입력은_400이고_티켓은_바뀌지_않는다() throws Exception {
		UUID ticket = analyze("함주헌");
		String title = items.findById(ticket).orElseThrow().getTitle();

		edit(ticket, "{\"assigneeMemberId\":999999}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TICKET_ASSIGNEE"));
		edit(ticket, "{\"title\":\"   \"}").andExpect(status().isBadRequest());
		edit(ticket, "{\"title\":\"" + "가".repeat(256) + "\"}").andExpect(status().isBadRequest());
		edit(ticket, "{\"due\":{\"date\":null,\"time\":\"18:00\"}}").andExpect(status().isBadRequest());
		edit(ticket, "{\"due\":{\"date\":\"10월 25일\"}}").andExpect(status().isBadRequest());

		TaskItem saved = items.findById(ticket).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo(title);
		assertThat(saved.getEditedJson()).isNull();
	}

	@Test
	void 담당자가_아니면_403_없는_티켓은_404_비로그인은_401이다() throws Exception {
		UUID mine = analyze("함주헌");
		UUID others = analyze("박종민"); // 가입하지 않은 멤버가 담당이다
		MockHttpSession stranger = new MockHttpSession();
		stranger.setAttribute(SessionConst.LOGIN_USER_ID,
				users.save(new User("tr" + UUID.randomUUID().toString().substring(0, 8), "hash", "다른사람")).getId());

		mvc.perform(get("/api/tickets/{id}", others).session(me))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TICKET_ACCESS_DENIED"));
		act(others, "confirm").andExpect(status().isForbidden());
		mvc.perform(post("/api/tickets/{id}/confirm", mine).session(stranger)).andExpect(status().isForbidden());
		mvc.perform(patch("/api/tickets/{id}", mine).session(stranger).contentType("application/json").content("{\"title\":\"x\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/tickets/{id}", UUID.randomUUID()).session(me))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
		mvc.perform(get("/api/tickets/{id}", mine)).andExpect(status().isUnauthorized());
		assertThat(items.findById(others).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
	}
}
