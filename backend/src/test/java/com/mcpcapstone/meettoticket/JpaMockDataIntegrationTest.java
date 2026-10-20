package com.mcpcapstone.meettoticket;

import com.mcpcapstone.meettoticket.domain.User;
import com.mcpcapstone.meettoticket.domain.Project;
import com.mcpcapstone.meettoticket.domain.ProjectMember;
import com.mcpcapstone.meettoticket.domain.Meeting;
import com.mcpcapstone.meettoticket.domain.AnalysisTask;
import com.mcpcapstone.meettoticket.domain.TaskItem;
import com.mcpcapstone.meettoticket.mock.MockDataInitializer;
import jakarta.persistence.EntityManager;
import com.mcpcapstone.meettoticket.service.AuthService;
import com.mcpcapstone.meettoticket.dto.LoginRequest;
import java.nio.charset.StandardCharsets;
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
import static org.assertj.core.api.Assertions.assertThat;

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
        var task = AnalysisTask.builder().id(taskId).meeting(meeting).status("PENDING")
            .input(Map.of("meetingId", meeting.getId(), "participants", List.of("임나윤"))).build();
        em.persist(task);
        em.persist(TaskItem.builder().id(itemId).task(task).workKey("CSV-TEST")
            .title("JSON 왕복 검증").content(Map.of("assignee", "jadoolim", "notes", List.of("검증")))
            .action("CREATE").reviewStatus("PENDING").publishStatus("NOT_STARTED").build());
        em.flush();
        em.clear();
        assertThat(em.find(AnalysisTask.class, taskId).getCreatedAt()).isNotNull();
        assertThat(em.find(AnalysisTask.class, taskId).getInput()).containsEntry("participants", List.of("임나윤"));
        var loaded = em.find(TaskItem.class, itemId);
        assertThat(loaded.getTask().getId()).isEqualTo(taskId);
        assertThat(loaded.getContent()).containsEntry("assignee", "jadoolim");
        assertThat(loaded.getGithubIssueNumber()).isNull();
    }

    private long count(String entity) {
        return em.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
    }
}
