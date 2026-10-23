package com.mcpcapstone.meettoticket.project;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.analysis.AnalysisTask;
import com.mcpcapstone.meettoticket.analysis.AnalysisTaskRepository;
import com.mcpcapstone.meettoticket.analysis.TaskItem;
import com.mcpcapstone.meettoticket.analysis.TaskItemRepository;
import com.mcpcapstone.meettoticket.analysis.TaskStatus;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisDispatch;
import com.mcpcapstone.meettoticket.queue.AnalysisDispatchRepository;
import com.mcpcapstone.meettoticket.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectDeletionTest {
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisDispatchRepository dispatches;

	private String name;
	private MockHttpSession admin;
	private MockHttpSession member;
	private long adminId;
	private long projectId;

	@BeforeEach
	void createProject() throws Exception {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		admin = signup("adm" + suffix);
		member = signup("mem" + suffix);
		adminId = userId(admin);
		long memberId = userId(member);
		name = "삭제 테스트 " + suffix;
		String res = mvc.perform(post("/api/projects").session(admin).contentType("application/json")
						.content("""
								{"name":"%s","description":null,"githubOwner":"MCP-Capstone","githubRepo":"del-%s",
								 "notionPageUrl":null,"slackChannelId":null,"memberUserIds":[%d]}""".formatted(name, suffix, memberId)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		projectId = json.read(res).path("projectId").longValue();
	}

	private MockHttpSession signup(String loginId) throws Exception {
		return (MockHttpSession) mvc.perform(post("/api/auth/signup").contentType("application/json")
						.content("{\"loginId\":\"%s\",\"password\":\"password1\",\"name\":\"%s\"}".formatted(loginId, loginId)))
				.andExpect(status().isCreated())
				.andReturn().getRequest().getSession(false);
	}

	private long userId(MockHttpSession session) throws Exception {
		return json.read(mvc.perform(get("/api/auth/me").session(session)).andReturn().getResponse().getContentAsString())
				.path("userId").longValue();
	}

	private ResultActions deleteProject(MockHttpSession session, String confirmName) throws Exception {
		return mvc.perform(delete("/api/projects/{id}", projectId)
				.session(session).contentType("application/json")
				.content("{\"confirmName\":\"%s\"}".formatted(confirmName)));
	}

	// 회의 1건과 분석 작업 1건(티켓 1건, 큐 발송 기록 1건)을 만든다.
	private AnalysisTask analysis(TaskStatus status) {
		Project project = projects.findById(projectId).orElseThrow();
		Meeting meeting = meetings.save(new Meeting(project, users.findById(adminId).orElseThrow(), "회의",
				LocalDate.of(2026, 10, 23), "[]", "meeting.txt", "원문"));
		AnalysisTask task = new AnalysisTask(UUID.randomUUID(), meeting, "{}");
		task.update(status, null, status.terminal() ? OffsetDateTime.now() : null, null);
		task = tasks.save(task);
		items.save(new TaskItem(task, new AgentContracts.Item(UUID.randomUUID(), null, "티켓", null, "CREATE", null,
				"APPROVED", "SUCCEEDED", 1, "https://github.com/MCP-Capstone/x/issues/1", null), "{}"));
		dispatches.save(new AnalysisDispatch(task.getId(), "MOCK"));
		return task;
	}

	@Test
	void 관리자가_이름을_맞게_입력하면_딸린_기록과_함께_삭제한다() throws Exception {
		AnalysisTask task = analysis(TaskStatus.COMPLETED);
		Long meetingId = task.getMeeting().getId();

		deleteProject(admin, " " + name + " ").andExpect(status().isNoContent());

		assertThat(projects.existsById(projectId)).isFalse();
		assertThat(members.findByProjectIdOrderById(projectId)).isEmpty();
		assertThat(meetings.existsById(meetingId)).isFalse();
		assertThat(tasks.existsById(task.getId())).isFalse();
		assertThat(items.findByTaskIdOrderById(task.getId())).isEmpty();
		assertThat(dispatches.existsById(task.getId())).isFalse();
		assertThat(users.existsById(adminId)).isTrue();
		mvc.perform(get("/api/projects/{id}", projectId).session(admin))
				.andExpect(status().isForbidden());
	}

	@Test
	void 관리자가_아니거나_이름이_다르면_삭제하지_않는다() throws Exception {
		deleteProject(member, name)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("PROJECT_ADMIN_REQUIRED"));
		deleteProject(admin, name + "x")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PROJECT_NAME_MISMATCH"));
		deleteProject(admin, " ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		assertThat(projects.existsById(projectId)).isTrue();
	}

	@Test
	void 처리_중인_회의록이_있으면_409다() throws Exception {
		analysis(TaskStatus.RUNNING);

		deleteProject(admin, name)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PROJECT_ANALYSIS_IN_PROGRESS"));
		assertThat(projects.existsById(projectId)).isTrue();
		assertThat(members.findByProjectIdOrderById(projectId)).hasSize(2);
	}
}
