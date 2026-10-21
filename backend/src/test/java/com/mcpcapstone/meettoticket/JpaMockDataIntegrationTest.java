package com.mcpcapstone.meettoticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.dto.LoginRequest;
import com.mcpcapstone.meettoticket.entity.AnalysisTask;
import com.mcpcapstone.meettoticket.entity.Meeting;
import com.mcpcapstone.meettoticket.entity.Project;
import com.mcpcapstone.meettoticket.entity.ProjectMember;
import com.mcpcapstone.meettoticket.entity.TaskItem;
import com.mcpcapstone.meettoticket.entity.User;
import com.mcpcapstone.meettoticket.entity.enums.AnalysisStatus;
import com.mcpcapstone.meettoticket.entity.enums.PublishStatus;
import com.mcpcapstone.meettoticket.entity.enums.ReviewStatus;
import com.mcpcapstone.meettoticket.entity.enums.TaskAction;
import com.mcpcapstone.meettoticket.mock.MockDataInitializer;
import com.mcpcapstone.meettoticket.service.AuthService;
import com.mcpcapstone.meettoticket.service.MeetingService;

import jakarta.persistence.EntityManager;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
	"spring.datasource.url=${TEST_DB_URL}", "spring.datasource.username=${TEST_DB_USERNAME}",
	"spring.datasource.password=${TEST_DB_PASSWORD:}", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@ActiveProfiles("mock")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
class JpaMockDataIntegrationTest {
	@Autowired EntityManager em;
	@Autowired AuthService authService;
	@Autowired MeetingService meetingService;
	@Autowired MockDataInitializer initializer;
	@Autowired JdbcTemplate jdbc;

	@Test
	void startupSeedsRelationsAndExactContentsAndCanRepeat() throws Exception {
		assertThat(authService.login(new LoginRequest("demo", "Demo1234!")).getName()).isEqualTo("임나윤");
		initializer.run(null);
		em.flush();
		em.clear();
		assertThat(count("User")).isEqualTo(1);
		assertThat(count("Project")).isEqualTo(1);
		assertThat(count("ProjectMember")).isEqualTo(6);
		assertThat(count("Meeting")).isEqualTo(3);
		assertThat(count("AnalysisTask")).isZero();
		assertThat(count("TaskItem")).isZero();
		var project = em.createQuery("select p from Project p", Project.class).getSingleResult();
		assertThat(project.getName()).isEqualTo("기업용 문서 협업 서비스 - 관리자 사용자 목록 CSV 내보내기");
		assertThat(project.getGithubOwner()).isEqualTo("MCP-Capstone");
		assertThat(project.getGithubRepo()).isEqualTo("mcp-capstone");
		var members = em.createQuery("select m from ProjectMember m", ProjectMember.class).getResultList();
		assertThat(members.stream().collect(Collectors.toMap(ProjectMember::getName, ProjectMember::getGithubName)))
			.containsExactlyInAnyOrderEntriesOf(Map.of(
				"강민규", "mingyukang5420", "박종민", "jongmiiin", "임나윤", "jadoolim",
				"최윤경", "0unk0", "함주헌", "juhun-ham", "허민규", "minkyu-huh"));
		Set<Long> ids = members.stream().map(ProjectMember::getId).collect(Collectors.toSet());
		for (var member : members) {
			assertThat(member.getProject().getId()).isEqualTo(project.getId());
			if (member.getName().equals("임나윤")) {
				assertThat(member.getUser().getLoginId()).isEqualTo("demo");
				assertThat(member.getUser().getPasswordHash()).startsWith("$2y$10$");
			} else assertThat(member.getUser()).isNull();
		}
		for (var meeting : em.createQuery("select m from Meeting m", Meeting.class).getResultList()) {
			assertThat(meeting.getParticipants()).containsExactlyInAnyOrderElementsOf(ids);
			assertThat(meeting.getProject().getId()).isEqualTo(project.getId());
			assertThat(meeting.getCreatedBy().getLoginId()).isEqualTo("demo");
			assertThat(meeting.getOriginalContent()).isEqualTo(new ClassPathResource(
				"mock/meetings/" + meeting.getOriginalFilename()).getContentAsString(StandardCharsets.UTF_8));
		}
		assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and data_type='json'", Long.class))
			.isEqualTo(3L);
		assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints where table_schema='public' and constraint_type='FOREIGN KEY'", Long.class))
			.isEqualTo(6L);
		assertThat(jdbc.queryForObject("select count(*) from pg_indexes where schemaname='public' and indexname in ('idx_meetings_project_date','idx_analysis_tasks_meeting','idx_task_items_task','idx_task_items_work_key')", Long.class))
			.isEqualTo(4L);
	}

	@Test
	void analysisAndItemUuidAndJsonRoundTrip() {
		var meeting = em.createQuery("select m from Meeting m order by m.id", Meeting.class).getResultList().getFirst();
		UUID taskId = UUID.randomUUID();
		UUID itemId = UUID.randomUUID();
		var task = AnalysisTask.builder().id(taskId).meeting(meeting).status(AnalysisStatus.QUEUED)
			.input(Map.of("meetingId", meeting.getId(), "participants", List.of("임나윤"))).build();
		em.persist(task);
		em.persist(TaskItem.builder().id(itemId).task(task).workKey("CSV-TEST")
			.title("JSON 왕복 검증").content(Map.of("assignee", "jadoolim", "notes", List.of("검증")))
			.action(TaskAction.CREATE).reviewStatus(ReviewStatus.PENDING).publishStatus(PublishStatus.NOT_STARTED).build());
		em.flush();
		em.clear();
		assertThat(em.find(AnalysisTask.class, taskId).getCreatedAt()).isNotNull();
		assertThat(em.find(AnalysisTask.class, taskId).getInput()).containsEntry("participants", List.of("임나윤"));
		var loaded = em.find(TaskItem.class, itemId);
		assertThat(loaded.getTask().getId()).isEqualTo(taskId);
		assertThat(loaded.getContent()).containsEntry("assignee", "jadoolim");
		assertThat(loaded.getGithubIssueNumber()).isNull();
		assertThat(loaded.getAction()).isEqualTo(TaskAction.CREATE);
		assertThat(loaded.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
		assertThat(loaded.getPublishStatus()).isEqualTo(PublishStatus.NOT_STARTED);
		assertThat(em.find(AnalysisTask.class, taskId).getStatus()).isEqualTo(AnalysisStatus.QUEUED);
		assertThat(jdbc.queryForObject("select action from task_items where task_item_id = ?", String.class, itemId))
			.isEqualTo("CREATE");
		assertThat(jdbc.queryForObject("select status from analysis_tasks where analysis_task_id = ?", String.class, taskId))
			.isEqualTo("QUEUED");
	}

	@Test
	void timestampsKeepInstantsAcrossDatabaseTimeZones() {
		var meeting = em.createQuery("select m from Meeting m order by m.id", Meeting.class).getResultList().getFirst();
		var user = meeting.getCreatedBy();
		var userCreatedAt = user.getCreatedAt().toInstant();
		var finishedAt = OffsetDateTime.of(2026, 10, 21, 9, 30, 0, 123000000, ZoneOffset.ofHours(9));
		UUID id = UUID.randomUUID();
		var task = AnalysisTask.builder().id(id).meeting(meeting).status(AnalysisStatus.COMPLETED)
			.input(Map.of()).finishedAt(finishedAt).build();
		em.persist(task);
		em.flush();
		em.refresh(task);
		var taskCreatedAt = task.getCreatedAt().toInstant();
		var userId = user.getId();

		jdbc.execute("SET LOCAL TIME ZONE 'America/Los_Angeles'");
		em.clear();
		var loaded = em.find(AnalysisTask.class, id);
		assertThat(loaded.getCreatedAt().toInstant()).isEqualTo(taskCreatedAt);
		assertThat(loaded.getFinishedAt().toInstant()).isEqualTo(finishedAt.toInstant());
		assertThat(em.find(User.class, userId).getCreatedAt().toInstant()).isEqualTo(userCreatedAt);
		assertThat(jdbc.queryForObject("""
			select count(*) from information_schema.columns
			where table_schema = 'public' and data_type = 'timestamp with time zone'
			  and ((table_name = 'users' and column_name = 'created_at')
				or (table_name = 'analysis_tasks' and column_name in ('created_at', 'finished_at')))
			""", Long.class)).isEqualTo(3L);
	}

	@Test
	void meetingSaveAcceptsMembersOfSameProject() {
		var template = firstMeeting();
		var saved = meetingService.save(meetingFor(template.getProject(), template.getParticipants()));
		em.flush();
		em.clear();
		assertThat(em.find(Meeting.class, saved.getId()).getParticipants())
			.containsExactlyElementsOf(template.getParticipants());
		assertThat(count("Meeting")).isEqualTo(4);
	}

	@Test
	void meetingSaveRejectsMembersOfAnotherProject() {
		var project = Project.builder().name("다른 프로젝트").githubOwner("test").githubRepo("other").build();
		em.persist(project);
		var member = ProjectMember.builder().project(project).name("외부 팀원").build();
		em.persist(member);
		assertInvalidParticipants(meetingFor(firstMeeting().getProject(), List.of(member.getId())));
	}

	@Test
	void meetingSaveRejectsUnknownMembers() {
		assertInvalidParticipants(meetingFor(firstMeeting().getProject(), List.of(Long.MAX_VALUE)));
	}

	@Test
	void meetingSaveRejectsDuplicateMembers() {
		var template = firstMeeting();
		Long memberId = template.getParticipants().getFirst();
		assertInvalidParticipants(meetingFor(template.getProject(), List.of(memberId, memberId)));
	}

	@Test
	void meetingSaveRejectsNullMembers() {
		var template = firstMeeting();
		assertInvalidParticipants(meetingFor(template.getProject(), Arrays.asList((Long) null)));
	}

	private void assertInvalidParticipants(Meeting meeting) {
		assertThatThrownBy(() -> meetingService.save(meeting)).isInstanceOf(BusinessException.class)
			.extracting("errorCode").isEqualTo(ErrorCode.INVALID_MEETING_PARTICIPANTS);
		assertThat(count("Meeting")).isEqualTo(3);
	}

	private Meeting firstMeeting() {
		return em.createQuery("select meeting from Meeting meeting order by meeting.id", Meeting.class)
			.getResultList().getFirst();
	}

	private Meeting meetingFor(Project project, List<Long> participantIds) {
		var template = firstMeeting();
		return Meeting.builder().project(project).createdBy(template.getCreatedBy())
			.title("참여자 검증").meetingDate(template.getMeetingDate()).participants(participantIds)
			.originalFilename("participants-test.txt").originalContent("테스트 회의").build();
	}

	private long count(String entity) {
		return em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
	}
}
