package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mcpcapstone.meettoticket.agent.AgentProperties;
import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.meeting.MeetingService;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeetingAnalysisRequestTest {
    // 참석자 줄과 회의 날짜 줄이 없는 회의록 (v16에서는 둘 다 화면에서 받거나 받지 않는다)
    private static final String NOTES = "# CSV 내보내기 점검\n\n박서연이 내일 오후 6시까지 API를 수정한다.\n";

    @MockitoBean AnalysisTaskPublisher publisher;
    @Autowired AnalysisTaskListener listener;
    @Autowired MockMvc mvc;
    @Autowired AnalysisCoordinator coordinator;
    @Autowired AnalysisStore store;
    @Autowired AnalysisTaskRepository tasks;
    @Autowired MeetingRepository meetings;
    @Autowired MeetingService meetingService;
    @Autowired ProjectRepository projects;
    @Autowired UserRepository users;
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

    private MockMultipartHttpServletRequestBuilder request() {
        return multipart("/api/projects/{id}/analysis-tasks", projectId);
    }

    private MockMultipartFile file(String name, String text) {
        return new MockMultipartFile("file", name, "text/markdown", text.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions perform(MockMultipartHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(builder.session(session));
    }

    private UUID accepted(ResultActions result) throws Exception {
        String body =
                result.andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.status").value("QUEUED"))
                        .andExpect(jsonPath("$.executionMode").value("MOCK"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        return UUID.fromString(json.read(body).path("taskId").asText());
    }

    private void invalid(MockMultipartHttpServletRequestBuilder builder) throws Exception {
        perform(builder)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_MEETING_INPUT"));
    }

    @Test
    void 붙여넣은_텍스트로_회의를_저장하고_분석을_접수한다() throws Exception {
        UUID id = accepted(perform(request().param("meetingDate", "2026-10-22").param("content", NOTES)));
        var task = tasks.findById(id).orElseThrow();
        var meeting = meetings.findById(task.getMeeting().getId()).orElseThrow();
        assertThat(meeting.getMeetingDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(meeting.getTitle()).isEqualTo("CSV 내보내기 점검");
        assertThat(meeting.getOriginalContent()).isEqualTo(NOTES);
        assertThat(meeting.getOriginalFilename()).isEmpty();
        assertThat(meeting.getParticipantsJson()).isEqualTo("[]");
        // 반환된 taskId로 기존 상태·결과 API가 그대로 동작한다
        mvc.perform(get("/api/analysis-tasks/{id}", id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));
        listener.receive(id.toString());
        coordinator.pollPending();
        coordinator.pollPending();
        mvc.perform(get("/api/analysis-tasks/{id}/items", id).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void md와_txt_파일을_받고_제목은_입력값_제목줄_파일이름_날짜순으로_정한다() throws Exception {
        UUID md = accepted(perform(request().file(file("회의록.md", NOTES)).param("meetingDate", "2026-10-22")));
        var mdMeeting = tasks.findById(md).orElseThrow().getMeeting();
        assertThat(meetings.findById(mdMeeting.getId()).orElseThrow().getOriginalFilename()).isEqualTo("회의록.md");
        assertThat(meetings.findById(mdMeeting.getId()).orElseThrow().getTitle()).isEqualTo("CSV 내보내기 점검");

        UUID txt = accepted(perform(request().file(file("주간회의.TXT", "제목줄 없는 회의록\n")).param("meetingDate", "2026-10-23")));
        assertThat(title(txt)).isEqualTo("주간회의");

        UUID given = accepted(perform(request().param("meetingDate", "2026-10-23").param("title", " 직접 입력 ").param("content", NOTES)));
        assertThat(title(given)).isEqualTo("직접 입력");

        UUID dated = accepted(perform(request().param("meetingDate", "2026-10-24").param("content", "제목줄 없음\n")));
        assertThat(title(dated)).isEqualTo("2026-10-24 회의");
    }

    private String title(UUID taskId) {
        return meetings.findById(tasks.findById(taskId).orElseThrow().getMeeting().getId()).orElseThrow().getTitle();
    }

    @Test
    void 회의_날짜와_회의록_입력을_검증한다() throws Exception {
        invalid(request().param("content", NOTES));
        invalid(request().param("meetingDate", "2026/10/22").param("content", NOTES));
        invalid(request().param("meetingDate", "2026-13-01").param("content", NOTES));
        invalid(request().param("meetingDate", "2026-10-22"));
        invalid(request().param("meetingDate", "2026-10-22").param("content", "  \n"));
        invalid(request().file(file("회의록.md", NOTES)).param("meetingDate", "2026-10-22").param("content", NOTES));
        invalid(request().file(file("회의록.pdf", NOTES)).param("meetingDate", "2026-10-22"));
        invalid(request().file(file("빈파일.md", "")).param("meetingDate", "2026-10-22"));
        invalid(request()
                .file(new MockMultipartFile("file", "깨진.md", "text/markdown", new byte[] {(byte) 0xC3, (byte) 0x28}))
                .param("meetingDate", "2026-10-22"));
    }

    @Test
    void 입력이_잘못되면_회의를_저장하지_않는다() throws Exception {
        long before = meetings.count();
        invalid(request().param("meetingDate", "2026-10-22"));
        invalid(request().file(file("회의록.pdf", NOTES)).param("meetingDate", "2026-10-22"));
        assertThat(meetings.count()).isEqualTo(before);
    }

    @Test
    void 비로그인과_프로젝트_외부사용자를_차단한다() throws Exception {
        mvc.perform(request().param("meetingDate", "2026-10-22").param("content", NOTES))
                .andExpect(status().isUnauthorized());
        MockHttpSession outsider = new MockHttpSession();
        outsider.setAttribute(
                SessionConst.LOGIN_USER_ID,
                users.save(new User("outsider-153", "hash", "외부인")).getId());
        long before = meetings.count();
        mvc.perform(request().param("meetingDate", "2026-10-22").param("content", NOTES).session(outsider))
                .andExpect(status().isForbidden());
        assertThat(meetings.count()).isEqualTo(before);
    }

    @Test
    @Transactional
    void GitHub_계정이_없는_멤버가_있어도_LIVE_분석을_접수한다() {
        // 데모 멤버는 GitHub 계정이 없다. 예전에는 LIVE에서 MEMBER_GITHUB_UNLINKED로 막았다
        Long userId = (Long) session.getAttribute(SessionConst.LOGIN_USER_ID);
        var meeting = meetingService.register(projectId, userId, "2026-10-22", null, NOTES, null);
        assertThatCode(() -> store.create(meeting.getId(), userId, "LIVE")).doesNotThrowAnyException();
    }
}
