package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mcpcapstone.meettoticket.agent.AgentProperties;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendWorkflowTest {
    @Autowired MockMvc mvc;
    @Autowired AnalysisCoordinator coordinator;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired TaskItemRepository items;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired AgentProperties properties;
    @Autowired JsonCodec json;
    private MockHttpSession session;
    private Long projectId;

    @BeforeEach
    void login() throws Exception {
        properties.setMockScenario("approved");
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType("application/json")
                                        .content(
                                                "{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        session = (MockHttpSession) result.getRequest().getSession(false);
        projectId =
                projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone")
                        .orElseThrow()
                        .getId();
    }

    private Long upload(String filename, String participants) throws Exception {
        String text =
                "# CSV 테스트\n회의 날짜: 2026-10-22\n회의 참여자: "
                        + participants
                        + "\n\n[구간 1] API를 내일 오후 6시까지 수정한다.\n";
        String body =
                mvc.perform(
                                multipart("/api/projects/{id}/meetings", projectId)
                                        .file(
                                                new MockMultipartFile(
                                                        "file",
                                                        filename,
                                                        "text/plain",
                                                        text.getBytes(StandardCharsets.UTF_8)))
                                        .session(session))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return json.read(body).path("meetingId").longValue();
    }

    private UUID start(Long meeting) throws Exception {
        String body =
                mvc.perform(post("/api/meetings/{id}/analysis-tasks", meeting).session(session))
                        .andExpect(status().isAccepted())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return UUID.fromString(json.read(body).path("taskId").asText());
    }

    private void finish() {
        coordinator.pollPending();
        coordinator.pollPending();
    }

    @Test
    void 로그인부터_파일저장_분석_결과조회까지_Agent없이_연결된다() throws Exception {
        mvc.perform(get("/api/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectId").value(projectId));
        Long meeting = upload("회의.txt", "박종민, 허민규");
        UUID task = start(meeting);
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(status().isConflict());
        coordinator.pollPending();
        mvc.perform(get("/api/analysis-tasks/{id}", task).session(session))
                .andExpect(jsonPath("$.status").value("RUNNING"));
        coordinator.pollPending();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionMode").value("MOCK"))
                .andExpect(jsonPath("$.summary.skipped").value(1))
                .andExpect(jsonPath("$.items[0].publishStatus").value("SKIPPED"))
                .andExpect(jsonPath("$.items[0].githubIssueUrl").isEmpty());
        long before = items.count();
        coordinator.pollPending();
        coordinator.pollPending();
        assertThat(items.count()).isEqualTo(before);
        mvc.perform(post("/api/meetings/{id}/analysis-tasks", meeting).session(session))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANALYSIS_ALREADY_REQUESTED"));
    }

    @Test
    void 비로그인_요청과_프로젝트_외부사용자를_차단한다() throws Exception {
        mvc.perform(get("/api/projects")).andExpect(status().isUnauthorized());
        MockHttpSession outsider = new MockHttpSession();
        outsider.setAttribute(
                SessionConst.LOGIN_USER_ID,
                users.save(new User("outsider", "hash", "외부인")).getId());
        mvc.perform(get("/api/projects/{id}/members", projectId).session(outsider))
                .andExpect(status().isForbidden());
        Long meeting = upload("접근.txt", "박종민");
        UUID task = start(meeting);
        finish();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(outsider))
                .andExpect(status().isForbidden());
    }

    @Test
    void 미등록_참여자와_확장자오류를_거절한다() throws Exception {
        mvc.perform(
                        multipart("/api/projects/{id}/meetings", projectId)
                                .file(
                                        new MockMultipartFile(
                                                "file", "회의.pdf", "text/plain", "test".getBytes()))
                                .session(session))
                .andExpect(status().isBadRequest());
        String text = "# CSV\n회의 날짜: 2026-10-22\n회의 참여자: 없는사람";
        mvc.perform(
                        multipart("/api/projects/{id}/meetings", projectId)
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "회의.txt",
                                                "text/plain",
                                                text.getBytes(StandardCharsets.UTF_8)))
                                .session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARTICIPANT_MAPPING_FAILED"));
    }

    @Test
    void 같은_파일명도_서로_다른_회의로_저장한다() throws Exception {
        assertThat(upload("동일.txt", "박종민")).isNotEqualTo(upload("동일.txt", "박종민"));
    }

    @Test
    void 업무가_없으면_빈_결과로_정상종료한다() throws Exception {
        properties.setMockScenario("empty");
        UUID task = start(upload("없음.txt", "박종민"));
        finish();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.summary.total").value(0));
    }

    @Test
    void 검수반려는_실패가_아니며_발행을_차단한다() throws Exception {
        properties.setMockScenario("rejected");
        UUID task = start(upload("반려.txt", "박종민"));
        finish();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].publishStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.items[0].contentJson.review.correctionCount").value(2));
    }

    @Test
    void 항목실패를_보존하고_부분실패를_구분한다() throws Exception {
        properties.setMockScenario("partial");
        UUID task = start(upload("부분.txt", "박종민"));
        finish();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(jsonPath("$.status").value("PARTIALLY_FAILED"))
                .andExpect(jsonPath("$.summary.failed").value(1))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void 승인항목_모두_발행실패하면_전체_FAILED다() throws Exception {
        properties.setMockScenario("failed");
        UUID task = start(upload("실패.txt", "박종민"));
        finish();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].reviewStatus").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].publishStatus").value("FAILED"));
    }
}
