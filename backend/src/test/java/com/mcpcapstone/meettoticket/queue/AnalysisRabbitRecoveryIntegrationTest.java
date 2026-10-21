package com.mcpcapstone.meettoticket.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.analysis.AnalysisStore;
import com.mcpcapstone.meettoticket.analysis.AnalysisTaskRepository;
import com.mcpcapstone.meettoticket.analysis.TaskStatus;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

/** 전용 vhost가 준비된 경우에만 실제 브로커에서 장애·DLQ·ACK 정책을 검증한다. */
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:rabbit81;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.rabbitmq.virtual-host=${RABBITMQ_REVIEW_VHOST:mtt81-review}",
	"app.analysis.queue-listener-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RABBITMQ_REVIEW_TESTS", matches = "true")
class AnalysisRabbitRecoveryIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JsonCodec json;
	@Autowired RabbitTemplate rabbit;
	@Autowired AmqpAdmin admin;
	@Autowired AnalysisTaskRepository tasks;
	@Autowired AnalysisDispatchRepository records;
	@MockitoSpyBean AnalysisDispatchStore dispatches;
	@MockitoSpyBean AnalysisStore analyses;
	@MockitoBean(name = "agentTransport") AgentGateway transport;
	@MockitoBean RetryDelay delay;
	MockHttpSession session;
	long projectId;

	@BeforeEach
	void setup() throws Exception {
		when(transport.mode()).thenReturn("MOCK");
		for (String queue : new String[] {AnalysisQueueConfiguration.QUEUE,
			AnalysisQueueConfiguration.DLQ, AnalysisQueueConfiguration.FAILURE_UPDATES})
			admin.purgeQueue(queue, false);
		var login = mvc.perform(post("/api/auth/login").contentType("application/json")
			.content("{\"loginId\":\"demo1\",\"password\":\"password1\"}"))
			.andExpect(status().isOk()).andReturn();
		session = (MockHttpSession) login.getRequest().getSession(false);
		var projects = mvc.perform(get("/api/projects").session(session)).andReturn();
		projectId = json.read(projects.getResponse().getContentAsString()).path("projects").path(0)
			.path("projectId").longValue();
	}

	private UUID start() throws Exception {
		String text = "# RabbitMQ DB 장애 검증\n회의 날짜: 2026-10-21\n회의 참여자: 박종민\nAPI 수정\n";
		var upload = mvc.perform(multipart("/api/projects/{id}/meetings", projectId)
			.file(new MockMultipartFile("file", "review81.txt", "text/plain",
				text.getBytes(StandardCharsets.UTF_8))).session(session))
			.andExpect(status().isCreated()).andReturn();
		long meeting = json.read(upload.getResponse().getContentAsString()).path("meetingId").longValue();
		var started = mvc.perform(post("/api/meetings/{id}/analysis-tasks", meeting).session(session))
			.andExpect(status().isAccepted()).andReturn();
		return UUID.fromString(json.read(started.getResponse().getContentAsString()).path("taskId").asText());
	}

	private long count(String queue) {
		return admin.getQueueInfo(queue).getMessageCount();
	}

	private void await(BooleanSupplier condition) throws Exception {
		long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
		assertThat(condition.getAsBoolean()).isTrue();
	}

	@Test
	void DB장애는_DLQ로_분리하고_복구후_FAILED를_기록하며_다음작업은_처리한다() throws Exception {
		doThrow(new DataAccessResourceFailureException("DB down"))
			.when(dispatches).claim(any(), eq("MOCK"));
		UUID failed = start();
		await(() -> count(AnalysisQueueConfiguration.DLQ) == 1
			&& count(AnalysisQueueConfiguration.FAILURE_UPDATES) == 1);
		verify(dispatches, times(4)).claim(failed, "MOCK");
		verify(transport, never()).submit(any());
		assertThat(tasks.findById(failed).orElseThrow().getStatus()).isEqualTo(TaskStatus.QUEUED);
		var repair = new AnalysisFailureReconciler(rabbit, dispatches);
		doThrow(new DataAccessResourceFailureException("DB still down"))
			.when(dispatches).failFromDeadLetter(failed);
		repair.reconcile();
		await(() -> count(AnalysisQueueConfiguration.FAILURE_UPDATES) == 1);
		doCallRealMethod().when(dispatches).failFromDeadLetter(failed);
		repair.reconcile();
		assertThat(tasks.findById(failed).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
		assertThat(count(AnalysisQueueConfiguration.FAILURE_UPDATES)).isZero();
		assertThat(count(AnalysisQueueConfiguration.DLQ)).isEqualTo(1);
		doCallRealMethod().when(dispatches).claim(any(), eq("MOCK"));
		UUID next = start();
		await(() -> records.findById(next).orElseThrow().getState() == AnalysisDispatch.State.ACCEPTED);
		verify(transport).submit(org.mockito.ArgumentMatchers.argThat(r -> r.taskId().equals(next)));
	}

	@Test
	void POST성공후_DB기록장애도_Agent를_재호출하지_않고_DLQ로_종료한다() throws Exception {
		doThrow(new DataAccessResourceFailureException("DB down after POST"))
			.when(dispatches).finish(any(), eq(AnalysisDispatch.State.ACCEPTED));
		UUID id = start();
		await(() -> count(AnalysisQueueConfiguration.DLQ) == 1
			&& count(AnalysisQueueConfiguration.FAILURE_UPDATES) == 1);
		verify(transport).submit(any());
		verify(dispatches, times(4)).finish(id, AnalysisDispatch.State.ACCEPTED);
		new AnalysisFailureReconciler(rabbit, dispatches).reconcile();
		assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
		verify(transport).submit(any());
	}

	@Test
	void 요청조회_DB가_복구되면_정상_수신을_이어가고_중복_POST하지_않는다() throws Exception {
		doThrow(new DataAccessResourceFailureException("temporary DB down"))
			.doCallRealMethod().when(analyses).request(any());
		UUID id = start();
		await(() -> records.findById(id).orElseThrow().getState() == AnalysisDispatch.State.ACCEPTED);
		verify(analyses, times(2)).request(id);
		verify(transport).submit(any());
		assertThat(count(AnalysisQueueConfiguration.DLQ)).isZero();
	}
}
