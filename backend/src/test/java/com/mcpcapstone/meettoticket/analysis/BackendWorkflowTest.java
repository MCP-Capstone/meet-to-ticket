package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mcpcapstone.meettoticket.agent.AgentProperties;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskPublisher;
import com.mcpcapstone.meettoticket.queue.AnalysisTaskListener;
import com.mcpcapstone.meettoticket.queue.AnalysisDispatchRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisDispatchStore;
import com.mcpcapstone.meettoticket.queue.AnalysisDispatch;
import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.agent.AgentException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;

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
import java.util.List;
import java.util.concurrent.*;

import com.mcpcapstone.meettoticket.agent.MockAgentGateway;
import com.mcpcapstone.meettoticket.common.error.BusinessException;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackendWorkflowTest {
    @MockitoBean
    AnalysisTaskPublisher publisher;
    @Autowired AnalysisTaskListener listener;
    @Autowired MockMvc mvc;
    @Autowired AnalysisCoordinator coordinator;
    @Autowired AnalysisStore store;
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
        UUID id = UUID.fromString(json.read(body).path("taskId").asText());
        // 202 수신 시점에는 Agent 실행 전이며, 폴링해도 QUEUED를 유지한다.
        coordinator.pollPending();
        mvc.perform(get("/api/analysis-tasks/{id}", id).session(session))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        listener.receive(id.toString());
        return id;
    }

    private void finish() {
        coordinator.pollPending();
        coordinator.pollPending();
    }

    @Test
    void 로그인부터_파일저장_분석_결과조회까지_Agent없이_연결된다() throws Exception {
        mvc.perform(get("/api/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].projectId").value(projectId))
                .andExpect(jsonPath("$.executionMode").value("MOCK"));
        mvc.perform(get("/api/projects/{id}/members", projectId).session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId))
                .andExpect(jsonPath("$.members.length()").value(6));
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
    void 기본_모의_실행은_발행_전_초안으로_끝난다() throws Exception {
        properties.setMockScenario("draft");
        UUID task = start(upload("초안.txt", "박종민"));
        finish();
        mvc.perform(get("/api/analysis-tasks/{id}/items", task).session(session))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.items[0].publishStatus").value("DRAFT"))
                .andExpect(jsonPath("$.items[0].githubIssueNumber").isEmpty())
                .andExpect(jsonPath("$.summary.drafts").value(1))
                .andExpect(jsonPath("$.summary.failed").value(0));
        // 진행 중에 받은 세부 정보가 상태 조회에 남는다
        mvc.perform(get("/api/analysis-tasks/{id}", task).session(session))
                .andExpect(jsonPath("$.progress.step").value("EXTRACTING"))
                .andExpect(jsonPath("$.progress.context.members").isNumber());
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

    @Test
    void 동시에_같은_회의를_분석해도_한_작업만_접수한다() throws Exception {
        Long meeting = upload("동시요청.txt", "박종민");
        Long userId = (Long) session.getAttribute(SessionConst.LOGIN_USER_ID);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<String> submit = () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return coordinator.start(meeting, userId).taskId().toString();
            } catch (BusinessException e) {
                return e.getErrorCode().name();
            }
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(submit);
            Future<String> second = executor.submit(submit);
            List<String> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(results).filteredOn("ANALYSIS_ALREADY_REQUESTED"::equals).hasSize(1);
            String id = results.stream().filter(r -> !r.equals("ANALYSIS_ALREADY_REQUESTED")).findFirst().orElseThrow();
            assertThat(store.request(UUID.fromString(id)).meeting().meetingId()).isEqualTo(meeting);
            // 요청 스냅샷으로 해당 회의에 저장된 작업 수를 확인한다.
            assertThat(tasks.findAll().stream()
                    .filter(t -> store.request(t.getId()).meeting().meetingId().equals(meeting)))
                    .hasSize(1);
        }
        finish();
    }

    @Test
    void MOCK_메모리를_잃으면_기존_작업을_재접수하지_않고_실패로_종료한다() throws Exception {
        UUID task = start(upload("재시작.txt", "박종민"));
        AnalysisCoordinator restarted = new AnalysisCoordinator(store, new MockAgentGateway(properties, json), properties);
        restarted.pollPending();
        mvc.perform(get("/api/analysis-tasks/{id}", task).session(session))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.errorMessage").value(org.hamcrest.Matchers.startsWith("AGENT_TASK_UNAVAILABLE")));
    }

    @Autowired AnalysisDispatchRepository dispatches;
    @Autowired AnalysisDispatchStore dispatchStore;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void 처리중과_완료후_중복메시지도_Agent를_재실행하지_않는다() throws Exception {
        UUID task = start(upload("중복메시지.txt", "박종민"));
        listener.receive(task.toString());
        finish();
        long before = items.count();
        listener.receive(task.toString());
        coordinator.pollPending();
        assertThat(items.count()).isEqualTo(before);
        assertThat(dispatches.findById(task).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void 최종접수실패는_영속상태와_작업을_함께_FAILED로_갱신한다() throws Exception {
        Long meeting = upload("접수실패.txt", "박종민");
        Long userId = (Long) session.getAttribute(SessionConst.LOGIN_USER_ID);
        UUID id = coordinator.start(meeting, userId).taskId();
        var transport = mock(AgentGateway.class);
        when(transport.mode()).thenReturn("MOCK");
        when(transport.submit(any()))
                .thenThrow(new AgentException(false));
        List<Long> waits = new ArrayList<>();
        var worker = new AnalysisTaskListener(
                transport, store, dispatchStore, waits::add);
        assertThatThrownBy(() -> worker.receive(id.toString()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(waits).containsExactly(2000L, 4000L, 8000L);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(dispatches.findById(id).orElseThrow().getState())
                .isEqualTo(AnalysisDispatch.State.EXHAUSTED);
        assertThat(dispatches.findById(id).orElseThrow().getAttempts()).isEqualTo(4);
        assertThatThrownBy(() -> worker.receive(id.toString()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verify(transport, times(4))
                .submit(any());
    }

    @Test
    void 요청트랜잭션이_롤백되면_작업과_발행기록을_모두_취소한다() throws Exception {
        Long meeting = upload("롤백.txt", "박종민");
        Long userId = (Long) session.getAttribute(SessionConst.LOGIN_USER_ID);
        var id = new AtomicReference<UUID>();
        var transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            id.set(coordinator.start(meeting, userId).taskId());
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.existsById(id.get())).isFalse();
        assertThat(dispatches.existsById(id.get())).isFalse();
        verify(publisher, never()).publish(id.get());
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("agentTransport")
    AgentGateway rawTransport;

    private UUID abandonedClaim(String filename) throws Exception {
        Long meeting = upload(filename, "박종민");
        Long userId = (Long) session.getAttribute(SessionConst.LOGIN_USER_ID);
        UUID id = coordinator.start(meeting, userId).taskId();
        assertThat(dispatchStore.claim(id, "MOCK")).isTrue();
        return id;
    }

    private void ageClaim(UUID id) {
        jdbc.update("update analysis_dispatches set updated_at = ? where task_id = ?",
                java.time.OffsetDateTime.now().minusMinutes(3), id);
    }

    @Test
    void JVM종료후_오래된_claim은_재POST없이_Agent기존결과를_회수한다() throws Exception {
        UUID id = abandonedClaim("중단된접수.txt");
        rawTransport.submit(store.request(id)); // Agent는 받았지만 Spring은 접수 결과 저장 전에 종료
        ageClaim(id);
        coordinator.pollPending();
        assertThat(dispatches.findById(id).orElseThrow().getState()).isEqualTo(AnalysisDispatch.State.UNCERTAIN);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING);
        coordinator.pollPending();
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(dispatches.findById(id).orElseThrow().getAttempts()).isZero();
    }

    @Test
    void Agent접수전_JVM종료도_조회실패로_종료하고_재POST하지_않는다() throws Exception {
        UUID id = abandonedClaim("전송전중단.txt");
        ageClaim(id);
        coordinator.pollPending();
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(dispatches.findById(id).orElseThrow().getAttempts()).isZero();
    }

    @Test
    void 최근_claim은_보호하고_시각이없는_기존행은_조회로_복구한다() throws Exception {
        UUID id = abandonedClaim("기존행.txt");
        coordinator.pollPending();
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(dispatches.findById(id).orElseThrow().getState()).isEqualTo(AnalysisDispatch.State.SUBMITTING);
        jdbc.update("update analysis_dispatches set updated_at = null where task_id = ?", id);
        coordinator.pollPending();
        assertThat(dispatches.findById(id).orElseThrow().getState()).isEqualTo(AnalysisDispatch.State.UNCERTAIN);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void DB복구후_DLQ실패기록을_멱등하게_반영하고_완료결과를_보존한다() throws Exception {
        UUID id = abandonedClaim("실패복구.txt");
        dispatchStore.failFromDeadLetter(id);
        dispatchStore.failFromDeadLetter(id);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(dispatches.findById(id).orElseThrow().getState()).isEqualTo(AnalysisDispatch.State.EXHAUSTED);
        UUID complete = start(upload("완료보존.txt", "박종민"));
        finish();
        dispatchStore.failFromDeadLetter(complete);
        assertThat(tasks.findById(complete).orElseThrow().getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(dispatches.findById(complete).orElseThrow().getState()).isEqualTo(AnalysisDispatch.State.ACCEPTED);
    }

}
