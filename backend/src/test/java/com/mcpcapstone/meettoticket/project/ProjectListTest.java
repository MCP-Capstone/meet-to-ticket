package com.mcpcapstone.meettoticket.project;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import org.springframework.transaction.annotation.Transactional;

import com.mcpcapstone.meettoticket.agent.RepositoryPermissionGateway;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.dto.ProjectDetail.ConnectionStatus;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectListTest {
	@Autowired MockMvc mvc;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired UserRepository users;
	@MockitoBean RepositoryPermissionGateway permissions;

	private User admin;
	private User member;
	private Project project;

	@BeforeEach
	void setup() {
		String suffix = UUID.randomUUID().toString();
		admin = users.save(new User("admin-" + suffix, "hash", "관리자"));
		member = users.save(new User("member-" + suffix, "hash", "멤버"));
		project = projects.save(new Project("프로젝트 목록", "owner", "list-" + suffix));
		members.save(new ProjectMember(project, admin, admin.getName(), null, ProjectRole.ADMIN));
		members.save(new ProjectMember(project, member, member.getName(), null));
		members.save(new ProjectMember(project, null, "미가입 팀원", null));
	}

	private MockHttpSession session(User user) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		return session;
	}

	private void meeting(Project target, String date) {
		meetings.save(new Meeting(target, admin, "목록 테스트 회의", LocalDate.parse(date), "[]", "test.txt", "본문"));
	}

	@Test
	void 소속_프로젝트의_저장소와_전체_멤버수와_최근_회의와_관리자_여부를_반환한다() throws Exception {
		Project empty = projects.save(new Project("회의 없음", "owner", "empty-" + UUID.randomUUID()));
		members.save(new ProjectMember(empty, admin, admin.getName(), null));
		Project other = projects.save(new Project("미소속", "owner", "other-" + UUID.randomUUID()));
		meeting(other, "2026-12-31");
		meeting(project, "2026-10-23");
		meeting(project, "2026-10-20"); // 저장 순서가 아니라 회의 날짜로 집계한다.

		mvc.perform(get("/api/projects").session(session(admin)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.executionMode").value("MOCK"))
				.andExpect(jsonPath("$.projects.length()").value(2))
				.andExpect(jsonPath("$.projects[0].projectId").value(project.getId()))
				.andExpect(jsonPath("$.projects[0].name").value("프로젝트 목록"))
				.andExpect(jsonPath("$.projects[0].githubOwner").value("owner"))
				.andExpect(jsonPath("$.projects[0].githubRepo").value(project.getGithubRepo()))
				.andExpect(jsonPath("$.projects[0].memberCount").value(3))
				.andExpect(jsonPath("$.projects[0].lastMeetingDate").value("2026-10-23"))
				.andExpect(jsonPath("$.projects[0].canEdit").value(true))
				.andExpect(jsonPath("$.projects[1].memberCount").value(1))
				.andExpect(jsonPath("$.projects[1].lastMeetingDate").value(nullValue()))
				.andExpect(jsonPath("$.projects[1].canEdit").value(false));
		mvc.perform(get("/api/projects").session(session(member)))
				.andExpect(jsonPath("$.projects.length()").value(1))
				.andExpect(jsonPath("$.projects[0].canEdit").value(false));
		verifyNoInteractions(permissions);
	}

	@Test
	void 연결_미확인과_미연결과_성공과_실패를_추정하지_않고_저장값으로_반환한다() throws Exception {
		mvc.perform(get("/api/projects").session(session(admin)))
				.andExpect(jsonPath("$.projects[0].connections.github").value(nullValue()))
				.andExpect(jsonPath("$.projects[0].connections.notion").value("NOT_CONNECTED"))
				.andExpect(jsonPath("$.projects[0].connections.slack").value("NOT_CONNECTED"));
		project.update(project.getName(), null, "owner", project.getGithubRepo(), "https://notion.so/test", "C123");
		project.recordGithubConnection(ConnectionStatus.ERROR);
		project.updateNotionConnectionStatus(ConnectionStatus.CONNECTED);
		projects.save(project);
		mvc.perform(get("/api/projects").session(session(admin)))
				.andExpect(jsonPath("$.projects[0].connections.github").value("ERROR"))
				.andExpect(jsonPath("$.projects[0].connections.notion").value("CONNECTED"))
				.andExpect(jsonPath("$.projects[0].connections.slack").value(nullValue()));
		project.recordGithubConnection(ConnectionStatus.CONNECTED);
		project.updateSlackConnectionStatus(ConnectionStatus.ERROR);
		projects.save(project);
		mvc.perform(get("/api/projects").session(session(admin)))
				.andExpect(jsonPath("$.projects[0].connections.github").value("CONNECTED"))
				.andExpect(jsonPath("$.projects[0].connections.slack").value("ERROR"));
		verifyNoInteractions(permissions);
	}

	@Test
	void 미소속_사용자는_빈_목록이고_비로그인은_401이다() throws Exception {
		User outsider = users.save(new User("out-" + UUID.randomUUID(), "hash", "외부 사용자"));
		mvc.perform(get("/api/projects").session(session(outsider)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.projects").isEmpty());
		mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
	}
}
