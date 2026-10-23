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
import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// 전송 결과 화면 (REQ-010, REQ-011, REQ-012, TASK-023·024·025). 데모 로그인 사용자(함주헌)가 회의록을 올린다.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DispatchTest {
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
	private Project project;

	@BeforeEach
	void login() throws Exception {
		me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
						.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
				.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
		project = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow();
	}

	// 회의록을 올리고 분석을 접수한다. finish=true면 모의 Agent 처리까지 끝낸다.
	private UUID analyze(String participants, boolean finish) throws Exception {
		String text = "# 전송 테스트\n회의 날짜: 2026-10-22\n회의 참여자: " + participants + "\n\n[구간 1] API를 내일 오후 6시까지 수정한다.\n";
		String upload = mvc.perform(multipart("/api/projects/{id}/meetings", project.getId())
						.file(new MockMultipartFile("file", "t.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)))
						.session(me))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long meeting = json.read(upload).path("meetingId").longValue();
		String started = mvc.perform(post("/api/meetings/{id}/analysis-tasks", meeting).session(me))
				.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		UUID task = UUID.fromString(json.read(started).path("taskId").asText());
		if (finish) {
			listener.receive(task.toString());
			coordinator.pollPending();
			coordinator.pollPending();
		}
		return task;
	}

	private TaskItem only(UUID task) {
		return items.findByTaskIdOrderById(task).get(0);
	}

	private ResultActions result(UUID task, MockHttpSession session) throws Exception {
		return mvc.perform(get("/api/analysis-tasks/{id}/tickets", task).session(session));
	}

	private ResultActions assign(UUID ticket, String body, MockHttpSession session) throws Exception {
		return mvc.perform(put("/api/tickets/{id}/assignee", ticket).session(session).contentType("application/json").content(body));
	}

	private Long memberId(String name) {
		return members.findByProjectIdOrderById(project.getId()).stream()
				.filter(m -> m.getName().equals(name)).findFirst().orElseThrow().getId();
	}

	private MockHttpSession otherMember() {
		User user = users.save(new User("dp" + UUID.randomUUID().toString().substring(0, 8), "hash", "다른 팀원"));
		members.save(new ProjectMember(project, user, user.getName(), null));
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		return session;
	}

	@Test
	void 업로더는_받은_사람과_상태와_미리보기를_본다() throws Exception {
		UUID task = analyze("함주헌", true);

		result(task, me)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.counts.total").value(1))
				.andExpect(jsonPath("$.counts.pendingReview").value(1))
				.andExpect(jsonPath("$.counts.assigneeRequired").value(0))
				.andExpect(jsonPath("$.tickets[0].ticket.status").value("PENDING_REVIEW"))
				.andExpect(jsonPath("$.tickets[0].ticket.publishTarget").value("GITHUB"))
				.andExpect(jsonPath("$.tickets[0].ticket.assignee.name").value("함주헌"))
				.andExpect(jsonPath("$.tickets[0].mine").value(true))
				.andExpect(jsonPath("$.tickets[0].preview.body").isNotEmpty())
				.andExpect(jsonPath("$.canAssign").value(true))
				.andExpect(jsonPath("$.members.length()").value(members.findByProjectIdOrderById(project.getId()).size()));
	}

	@Test
	void 건수는_연결과_담당_지정_필요를_나누고_GitHub_티켓만_미리보기가_있다() throws Exception {
		UUID linked = analyze("함주헌", true);
		TaskItem item = only(linked);
		item.changePublication(PublishTarget.GITHUB, ProcessingType.LINK);
		items.save(item);
		result(linked, me)
				.andExpect(jsonPath("$.counts.linked").value(1))
				.andExpect(jsonPath("$.counts.pendingReview").value(0));

		UUID notion = analyze("함주헌", true);
		item = only(notion);
		item.changePublication(PublishTarget.NOTION, ProcessingType.CREATE);
		item.assignTo(null);
		items.save(item);
		result(notion, me)
				.andExpect(jsonPath("$.counts.assigneeRequired").value(1))
				.andExpect(jsonPath("$.tickets[0].ticket.assignee").isEmpty())
				.andExpect(jsonPath("$.tickets[0].preview").isEmpty());
	}

	@Test
	void 업로더가_대표_담당을_지정하면_그_팀원의_검토_대기가_된다() throws Exception {
		UUID task = analyze("함주헌", true);
		TaskItem item = only(task);
		item.assignTo(null); // 담당자가 여러 명이거나 정해지지 않은 티켓
		items.save(item);
		Long choi = memberId("최윤경");

		assign(item.getId(), "{\"memberId\":" + choi + "}", me)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ticket.status").value("PENDING_REVIEW"))
				.andExpect(jsonPath("$.ticket.assignee.name").value("최윤경"))
				.andExpect(jsonPath("$.mine").value(false));
		TaskItem saved = items.findById(item.getId()).orElseThrow();
		assertThat(saved.getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(saved.getAssignee().getId()).isEqualTo(choi);
		// 이미 보낸 티켓은 다시 지정하지 않는다
		assign(item.getId(), "{\"memberId\":" + choi + "}", me)
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_STATUS_CONFLICT"));
	}

	@Test
	void 대표_담당_지정은_업로더만_같은_프로젝트_팀원으로_한다() throws Exception {
		UUID task = analyze("함주헌", true);
		TaskItem item = only(task);
		item.assignTo(null);
		items.save(item);
		MockHttpSession other = otherMember();

		// 다른 팀원은 결과를 볼 수 있지만 지정할 수 없다
		result(task, other)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.canAssign").value(false))
				.andExpect(jsonPath("$.members.length()").value(0));
		assign(item.getId(), "{\"memberId\":" + memberId("최윤경") + "}", other)
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TICKET_UPLOADER_REQUIRED"));
		assign(item.getId(), "{\"memberId\":999999}", me)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROJECT_MEMBER_NOT_FOUND"));
		assign(item.getId(), "{}", me).andExpect(status().isBadRequest());
		assign(UUID.randomUUID(), "{\"memberId\":1}", me).andExpect(status().isNotFound());
		assertThat(items.findById(item.getId()).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.ASSIGNEE_REQUIRED);
	}

	@Test
	void 처리_중이면_409_없는_작업은_404_프로젝트_밖은_403_비로그인은_401() throws Exception {
		UUID running = analyze("함주헌", false);
		result(running, me).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_NOT_FINISHED"));
		result(UUID.randomUUID(), me).andExpect(status().isNotFound());

		UUID done = analyze("함주헌", true);
		MockHttpSession stranger = new MockHttpSession();
		stranger.setAttribute(SessionConst.LOGIN_USER_ID,
				users.save(new User("ds" + UUID.randomUUID().toString().substring(0, 8), "hash", "외부인")).getId());
		result(done, stranger).andExpect(status().isForbidden());
		mvc.perform(get("/api/analysis-tasks/{id}/tickets", done)).andExpect(status().isUnauthorized());
	}
}
