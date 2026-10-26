package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.agent.TicketPreviewGateway;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ticket-preview;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "app.demo.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketPreviewTest {
	@MockitoBean TicketPreviewGateway preview;
	@MockitoBean PublishGateway publish;
	@Autowired MockMvc mvc;
	@Autowired TaskItemRepository items;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;
	private TaskItem item;
	private MockHttpSession session;
	private String content;

	@BeforeEach
	void setup() {
		User user = users.save(new User("preview" + UUID.randomUUID(), "hash", "나윤"));
		Project project = projects.save(new Project("미리보기", "owner", "repo" + UUID.randomUUID()));
		ProjectMember member = members.save(new ProjectMember(project, user, "나윤", "jadoolim"));
		Meeting meeting = meetings.save(new Meeting(project, user, "회의", LocalDate.of(2026, 10, 23), "[]", "t.txt", "# 회의\n\n## 결정\n오늘 회의 원문을 남긴다."));
		AnalysisTask task = tasks.save(new AnalysisTask(UUID.randomUUID(), meeting, "{}"));
		content = """
			{"description":"저장된 수정 본문","tasks":["API 작성"],"acceptance_criteria":["오류 안내"],
			 "evidence_quotes":["회의 원문"],"open_questions":["응답 형식 확인"],
			 "assignees":{"names":["나윤"],"github":["jadoolim"],"unresolved":[]},
			 "due":{"date":"2026-10-30","time":"14:00"},
			 "slack":{"text":"수정 전 Slack 공지"}}
			""";
		var result = new AgentContracts.Item(UUID.randomUUID(), null, "저장된 제목", json.read(content), "CREATE", null, "APPROVED", "SKIPPED", null, null, null);
		item = new TaskItem(task, result, content);
		item.assignTo(member);
		item.moveTo(TicketStatus.PENDING_REVIEW);
		item = items.save(item);
		session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
		when(preview.render(any())).thenReturn(new TicketPreviewGateway.View("CREATE", "## 최종 본문", null, List.of(), List.of("jadoolim"), null));
	}

	@Test
	void 저장된_백로그와_발행과_동일한_스냅샷을_조회하되_상태는_바꾸지_않는다() throws Exception {
		mvc.perform(get("/api/tickets/{id}/preview", item.getId()).session(session))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
			.andExpect(jsonPath("$.publication.body").value("## 최종 본문"))
			.andExpect(jsonPath("$.backlog.description").value("저장된 수정 본문"))
			.andExpect(jsonPath("$.backlog.tasks[0]").value("API 작성"))
			.andExpect(jsonPath("$.backlog.acceptanceCriteria[0]").value("오류 안내"))
			.andExpect(jsonPath("$.backlog.evidenceQuotes[0]").value("회의 원문"))
			// 근거 문장이 들어 있는 회의록 단락만 제목과 함께 내려 준다.
			.andExpect(jsonPath("$.evidenceExcerpts[0].heading").value("결정"))
			.andExpect(jsonPath("$.evidenceExcerpts[0].text").value("오늘 회의 원문을 남긴다."))
			.andExpect(jsonPath("$.evidenceExcerpts[0].quotes[0]").value("회의 원문"))
			.andExpect(jsonPath("$.backlog.openQuestions[0]").value("응답 형식 확인"));
		var captor = ArgumentCaptor.forClass(PublishGateway.Ticket.class);
		verify(preview).render(captor.capture());
		assertThat(captor.getValue().contentJson().path("due").path("date").asText()).isEqualTo("2026-10-30");
		assertThat(captor.getValue().contentJson().path("assignees").path("github").get(0).asText()).isEqualTo("jadoolim");
		unchanged();
	}

	@Test
	void 담당자만_조회하고_로그인과_존재여부를_검사한다() throws Exception {
		mvc.perform(get("/api/tickets/{id}/preview", item.getId())).andExpect(status().isUnauthorized());
		MockHttpSession other = new MockHttpSession();
		User outsider = users.save(new User("other" + UUID.randomUUID(), "hash", "다른 사람"));
		other.setAttribute(SessionConst.LOGIN_USER_ID, outsider.getId());
		mvc.perform(get("/api/tickets/{id}/preview", item.getId()).session(other)).andExpect(status().isForbidden());
		mvc.perform(get("/api/tickets/{id}/preview", UUID.randomUUID()).session(session)).andExpect(status().isNotFound());
		verifyNoInteractions(preview);
		unchanged();
	}

	@Test
	void Agent_조회_실패도_상태와_저장_본문에_영향이_없다() throws Exception {
		when(preview.render(any())).thenThrow(new AgentException(true));
		mvc.perform(get("/api/tickets/{id}/preview", item.getId()).session(session))
			.andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("TICKET_PREVIEW_UNAVAILABLE"));
		unchanged();
	}

	@Test
	void 다른_도구는_백로그만_보여주며_Agent_발행_미리보기를_호출하지_않는다() throws Exception {
		ReflectionTestUtils.setField(item, "publishTarget", PublishTarget.NOTION);
		items.save(item);
		mvc.perform(get("/api/tickets/{id}/preview", item.getId()).session(session))
			.andExpect(status().isOk()).andExpect(jsonPath("$.publishTarget").value("NOTION"))
			.andExpect(jsonPath("$.publication").isEmpty()).andExpect(jsonPath("$.backlog.tasks[0]").value("API 작성"));
		verifyNoInteractions(preview);
		unchanged();
	}

	@Test
	void Slack은_저장한_본문을_실제_전송_기준으로_미리보기한다() throws Exception {
		ReflectionTestUtils.setField(item, "publishTarget", PublishTarget.SLACK);
		items.save(item);

		mvc.perform(get("/api/tickets/{id}/preview", item.getId()).session(session))
			.andExpect(status().isOk()).andExpect(jsonPath("$.publishTarget").value("SLACK"))
			.andExpect(jsonPath("$.publication.kind").value("CREATE"))
			.andExpect(jsonPath("$.publication.body").value("저장된 수정 본문"));

		verifyNoInteractions(preview);
		unchanged();
	}

	private void unchanged() {
		TaskItem saved = items.findById(item.getId()).orElseThrow();
		assertThat(saved.getTicketStatus()).isEqualTo(TicketStatus.PENDING_REVIEW);
		assertThat(saved.getContentJson()).isEqualTo(content);
		verifyNoInteractions(publish);
	}
}
