package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;

// 요청은 한 번만 Agent에 보내고 콜백을 재전송해도 결과가 덮어써지지 않는지 확인한다.
@SpringBootTest(properties = "BACKEND_INTERNAL_TOKEN=test-internal")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicationBatchTest {
    @MockitoBean AnalysisTaskPublisher publisher;
    @MockitoBean PublishGateway publishGateway;
    @Autowired AnalysisTaskListener listener;
    @Autowired AnalysisCoordinator coordinator;
    @Autowired TaskItemRepository items;
    @Autowired ProjectRepository projects;
    @Autowired MockMvc mvc;
    @Autowired JsonCodec json;

    private MockHttpSession me;
    private UUID ticket;

    @BeforeEach
    void setUp() throws Exception {
        me = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        Long projectId = projects.findByGithubOwnerAndGithubRepo("MCP-Capstone", "mcp-capstone").orElseThrow().getId();
        String text = "# 발행\n회의 날짜: 2026-10-22\n회의 참여자: 함주헌\n\n[구간 1] API를 수정한다.\n";
        String uploaded = mvc.perform(multipart("/api/projects/{id}/meetings", projectId)
                        .file(new MockMultipartFile("file", "p.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)))
                        .session(me))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long meetingId = json.read(uploaded).path("meetingId").longValue();
        String started = mvc.perform(post("/api/meetings/{id}/analysis-tasks", meetingId).session(me))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID taskId = UUID.fromString(json.read(started).path("taskId").asText());
        listener.receive(taskId.toString());
        coordinator.pollPending();
        coordinator.pollPending();
        ticket = items.findByTaskIdOrderById(taskId).get(0).getId();
        mvc.perform(post("/api/tickets/{id}/confirm", ticket).session(me)).andExpect(status().isOk());
    }

    @Test
    void 확인된_내_티켓을_한번만_접수하고_콜백으로_발행_결과를_저장한다() throws Exception {
        String accepted = mvc.perform(post("/api/tickets/publish").session(me))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.ticketCount").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(json.read(accepted).path("batchId").asText());
        ArgumentCaptor<AgentContracts.PublishBatchRequest> request = ArgumentCaptor.forClass(AgentContracts.PublishBatchRequest.class);
        verify(publishGateway).submit(request.capture());
        assertThat(request.getValue().batchId()).isEqualTo(batchId);
        assertThat(request.getValue().tickets()).singleElement().satisfies(sent -> {
            assertThat(sent.ticketId()).isEqualTo(ticket);
            assertThat(sent.reviewStatus()).isEqualTo("CONFIRMED");
            assertThat(sent.project().githubRepo()).isEqualTo("mcp-capstone");
        });
        assertThat(items.findById(ticket).orElseThrow().getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);

        mvc.perform(post("/api/tickets/publish").session(me))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_CONFIRMED_TICKETS"));
        verifyNoMoreInteractions(publishGateway);

        String callback = """
                {"batchId":"%s","status":"SUCCEEDED","summary":{"PUBLISHED":1},"items":[{
                  "ticketId":"%s","processingType":"CREATE","status":"PUBLISHED",
                  "githubIssueNumber":91,"githubIssueUrl":"https://github.com/MCP-Capstone/mcp-capstone/issues/91",
                  "droppedLabels":[],"retryable":false
                }]}""".formatted(batchId, ticket);
        mvc.perform(post("/internal/publish-batches/{id}/result", batchId)
                        .header("X-Internal-Token", "test-internal")
                        .contentType("application/json").content(callback))
                .andExpect(status().isAccepted());
        TaskItem published = items.findById(ticket).orElseThrow();
        assertThat(published.getTicketStatus()).isEqualTo(TicketStatus.PUBLISHED);
        assertThat(published.getPublishStatus()).isEqualTo("SUCCEEDED");
        assertThat(published.getGithubIssueNumber()).isEqualTo(91);

        // Agent reporter는 네트워크 오류 뒤 같은 결과를 다시 보낼 수 있다.
        mvc.perform(post("/internal/publish-batches/{id}/result", batchId)
                        .header("X-Internal-Token", "test-internal")
                        .contentType("application/json").content(callback))
                .andExpect(status().isAccepted());
        assertThat(items.findById(ticket).orElseThrow().getGithubIssueNumber()).isEqualTo(91);
    }

    @Test
    void 응답에_없는_티켓은_UNKNOWN으로_보존해_자동_재발행하지_않는다() throws Exception {
        String accepted = mvc.perform(post("/api/tickets/publish").session(me))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(json.read(accepted).path("batchId").asText());
        String callback = "{\"batchId\":\"%s\",\"status\":\"FAILED\",\"summary\":{},\"items\":[]}".formatted(batchId);
        mvc.perform(post("/internal/publish-batches/{id}/result", batchId)
                        .header("X-Internal-Token", "test-internal")
                        .contentType("application/json").content(callback))
                .andExpect(status().isAccepted());
        TaskItem unknown = items.findById(ticket).orElseThrow();
        assertThat(unknown.getTicketStatus()).isEqualTo(TicketStatus.PUBLISHING);
        assertThat(unknown.getPublishStatus()).isEqualTo("UNKNOWN");
        assertThat(unknown.getErrorMessage()).contains("발행 결과");
    }

    @Test
    void Notion_발행_결과의_페이지_식별자와_URL을_저장한다() throws Exception {
        String accepted = mvc.perform(post("/api/tickets/publish").session(me))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID batchId = UUID.fromString(json.read(accepted).path("batchId").asText());
        String callback = """
                {"batchId":"%s","status":"SUCCEEDED","summary":{"PUBLISHED":1},"items":[{
                  "ticketId":"%s","processingType":"CREATE","status":"PUBLISHED",
                  "notionPageId":"notion-page-id","notionPageUrl":"https://www.notion.so/notion-page-id",
                  "droppedLabels":[],"retryable":false
                }]}""".formatted(batchId, ticket);

        mvc.perform(post("/internal/publish-batches/{id}/result", batchId)
                        .header("X-Internal-Token", "test-internal")
                        .contentType("application/json").content(callback))
                .andExpect(status().isAccepted());

        TaskItem published = items.findById(ticket).orElseThrow();
        assertThat(published.getNotionPageId()).isEqualTo("notion-page-id");
        assertThat(published.getNotionPageUrl()).isEqualTo("https://www.notion.so/notion-page-id");
    }
}
