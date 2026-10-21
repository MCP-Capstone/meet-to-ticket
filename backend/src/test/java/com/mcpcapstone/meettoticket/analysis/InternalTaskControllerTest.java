package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

@SpringBootTest(properties = {
		"BACKEND_INTERNAL_TOKEN=progress-test-token",
		"spring.datasource.url=jdbc:h2:mem:internal-progress;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
		"app.demo.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalTaskControllerTest {

	private static final String TOKEN = "progress-test-token";
	@Autowired MockMvc mvc;
	@Autowired AnalysisStore store;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired TaskItemRepository items;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;
	private UUID taskId;
	private Long meetingId;
	private Project project;
	private MockHttpSession session;

	@BeforeEach
	void createTask() {
		String suffix = UUID.randomUUID().toString();
		User user = users.save(new User("progress-" + suffix, "unused-test-hash", "진행 테스트"));
		project = projects.save(new Project("진행 보고 테스트", "test-owner", "progress-" + suffix));
		ProjectMember member = members.save(new ProjectMember(project, user, user.getName(), "test-member"));
		Meeting meeting = meetings.save(new Meeting(project, user, "진행 보고 회의", LocalDate.of(2026, 10, 22),
				json.write(List.of(member.getId())), "progress.txt", "진행 보고 테스트 원문"));
		meetingId = meeting.getId();
		taskId = store.create(meetingId, user.getId(), "LIVE").taskId();
		session = new MockHttpSession();
		session.setAttribute(SessionConst.LOGIN_USER_ID, user.getId());
	}

	@ParameterizedTest
	@EnumSource(value = TaskStage.class, names = {"EXTRACTING", "NORMALIZING", "REVIEWING", "PUBLISHING"})
	void Agent_보고를_세션_없이_받고_공개_조회에_단계를_반영한다(TaskStage stage) throws Exception {
		report(stage);
		mvc.perform(get("/api/analysis-tasks/{id}", taskId).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("RUNNING"))
				.andExpect(jsonPath("$.stage").value(stage.name()))
				.andExpect(jsonPath("$.finishedAt").isEmpty());
		assertThat(items.findByTaskIdOrderById(taskId)).isEmpty();
	}

	@Test
	void Bearer_토큰으로도_진행_보고를_받는다() throws Exception {
		mvc.perform(post("/internal/tasks/{id}/progress", taskId)
				.header("Authorization", "Bearer " + TOKEN)
				.contentType("application/json")
				.content(progress(TaskStage.REVIEWING)))
				.andExpect(status().isAccepted());
		assertThat(task().getStage()).isEqualTo(TaskStage.REVIEWING);
	}

	@ParameterizedTest
	@ValueSource(strings = {"progress", "result"})
	void 로그인_세션으로_토큰_검증을_우회할_수_없다(String endpoint) throws Exception {
		for (String header : List.of("none", "X-Internal-Token", "Authorization")) {
			var request = post("/internal/tasks/{id}/{endpoint}", taskId, endpoint)
					.session(session).contentType("application/json").content(progress(TaskStage.REVIEWING));
			if (!header.equals("none")) {
				request.header(header, header.equals("Authorization") ? "Bearer wrong-token" : "wrong-token");
			}
			mvc.perform(request).andExpect(status().isUnauthorized());
		}
		assertThat(task().getStatus()).isEqualTo(TaskStatus.QUEUED);
		assertThat(task().getStage()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"progress", "result"})
	void 서버_토큰이_비어_있으면_보고를_거절한다(String endpoint) throws Exception {
		MockMvc unconfigured = MockMvcBuilders.standaloneSetup(new InternalTaskController(store, "")).build();
		unconfigured.perform(post("/internal/tasks/{id}/{endpoint}", taskId, endpoint)
				.header("X-Internal-Token", TOKEN)
				.contentType("application/json").content(progress(TaskStage.REVIEWING)))
				.andExpect(status().isServiceUnavailable());
		assertThat(task().getStatus()).isEqualTo(TaskStatus.QUEUED);
	}

	@Test
	void 잘못된_상태와_단계를_거절한다() throws Exception {
		for (String body : List.of(
				"{\"status\":\"COMPLETED\",\"stage\":\"REVIEWING\"}",
				"{\"status\":\"QUEUED\",\"stage\":\"EXTRACTING\"}",
				"{\"status\":\"RUNNING\",\"stage\":\"VERIFYING\"}",
				"{\"status\":\"RUNNING\",\"stage\":\"TYPO\"}",
				"{\"status\":\"RUNNING\"}",
				"{\"stage\":\"REVIEWING\"}",
				"{}")) {
			mvc.perform(post("/internal/tasks/{id}/progress", taskId)
					.header("X-Internal-Token", TOKEN).contentType("application/json").content(body))
					.andExpect(status().isBadRequest());
		}
		assertThat(task().getStatus()).isEqualTo(TaskStatus.QUEUED);
		assertThat(task().getStage()).isNull();
	}

	@Test
	void 없는_작업의_진행_보고는_404를_반환한다() throws Exception {
		mvc.perform(post("/internal/tasks/{id}/progress", UUID.randomUUID())
				.header("X-Internal-Token", TOKEN).contentType("application/json")
				.content(progress(TaskStage.EXTRACTING)))
				.andExpect(status().isNotFound());
	}

	@Test
	void 중복되거나_늦게_도착한_진행_보고로_단계가_되돌아가지_않는다() throws Exception {
		OffsetDateTime createdAt = task().getCreatedAt();
		for (TaskStage stage : List.of(TaskStage.EXTRACTING, TaskStage.NORMALIZING, TaskStage.REVIEWING,
				TaskStage.PUBLISHING, TaskStage.REVIEWING, TaskStage.EXTRACTING, TaskStage.PUBLISHING)) {
			report(stage);
		}
		AnalysisTask task = task();
		assertThat(task.getStatus()).isEqualTo(TaskStatus.RUNNING);
		assertThat(task.getStage()).isEqualTo(TaskStage.PUBLISHING);
		assertThat(task.getCreatedAt()).isEqualTo(createdAt);
		assertThat(task.getFinishedAt()).isNull();
		assertThat(task.getErrorMessage()).isNull();
	}

	@ParameterizedTest
	@EnumSource(value = TaskStatus.class, names = {"COMPLETED", "PARTIALLY_FAILED", "FAILED"})
	void 종료된_작업은_진행_보고를_무시한다(TaskStatus status) throws Exception {
		AnalysisTask task = task();
		task.update(status, TaskStage.VERIFYING, OffsetDateTime.now(), "기존 종료 사유");
		tasks.saveAndFlush(task);
		OffsetDateTime finishedAt = task().getFinishedAt();
		report(TaskStage.REVIEWING);
		AnalysisTask unchanged = task();
		assertThat(unchanged.getStatus()).isEqualTo(status);
		assertThat(unchanged.getStage()).isEqualTo(TaskStage.VERIFYING);
		assertThat(unchanged.getFinishedAt()).isEqualTo(finishedAt);
		assertThat(unchanged.getErrorMessage()).isEqualTo("기존 종료 사유");
	}

	@ParameterizedTest
	@ValueSource(strings = {"X-Internal-Token", "Authorization"})
	void 결과_보고는_202만_반환하고_최종_저장은_기존_폴링_처리에_맡긴다(String header) throws Exception {
		String token = header.equals("Authorization") ? "Bearer " + TOKEN : TOKEN;
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/internal/tasks/{id}/result", taskId)
					.header(header, token).contentType("application/json")
					.content("{\"taskId\":\"" + taskId + "\",\"status\":\"SUCCEEDED\",\"items\":[{}]}"))
					.andExpect(status().isAccepted());
		}
		assertThat(task().getStatus()).isEqualTo(TaskStatus.QUEUED);
		assertThat(task().getStage()).isNull();
		assertThat(items.findByTaskIdOrderById(taskId)).isEmpty();

		// 실제 Agent나 GitHub를 호출하지 않고, 폴링이 사용하는 기존 apply()의 저장 동작을 확인한다.
		AgentContracts.Item item = new AgentContracts.Item(UUID.randomUUID(), null, "발행 테스트",
				json.read("{\"description\":\"테스트 결과\"}"), "CREATE", null, "APPROVED", "SUCCEEDED", 1,
				"https://github.com/" + project.getGithubOwner() + "/" + project.getGithubRepo() + "/issues/1", null);
		store.apply(taskId, new AgentContracts.Result(taskId, meetingId, TaskStatus.COMPLETED, TaskStage.VERIFYING,
				task().getCreatedAt(), OffsetDateTime.now(), List.of(item),
				new AgentContracts.Summary(1, 1, 0, 1, 0, 0, 0), null));
		assertThat(task().getStatus()).isEqualTo(TaskStatus.COMPLETED);
		assertThat(items.findByTaskIdOrderById(taskId)).hasSize(1);
		mvc.perform(get("/api/analysis-tasks/{id}/items", taskId).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.summary.succeeded").value(1));
	}

	private void report(TaskStage stage) throws Exception {
		mvc.perform(post("/internal/tasks/{id}/progress", taskId)
				.header("X-Internal-Token", TOKEN).contentType("application/json").content(progress(stage)))
				.andExpect(status().isAccepted());
	}

	private String progress(TaskStage stage) {
		return "{\"status\":\"RUNNING\",\"stage\":\"" + stage.name() + "\"}";
	}

	private AnalysisTask task() {
		return tasks.findById(taskId).orElseThrow();
	}
}
