package com.mcpcapstone.meettoticket.queue;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.analysis.AnalysisStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import java.util.List;
import java.util.UUID;

class AnalysisTaskListenerTest {
	AgentGateway transport = mock(AgentGateway.class);
	AnalysisStore analyses = mock(AnalysisStore.class);
	AnalysisDispatchStore dispatches = mock(AnalysisDispatchStore.class);
	RetryDelay delay = mock(RetryDelay.class);
	AnalysisTaskListener listener = new AnalysisTaskListener(transport, analyses, dispatches, delay);
	UUID id = UUID.randomUUID();
	AgentContracts.StartRequest request = new AgentContracts.StartRequest(id, null, null, List.of());

	@BeforeEach
	void setup() {
		when(transport.mode()).thenReturn("LIVE");
		when(dispatches.claim(id, "LIVE")).thenReturn(true);
		when(analyses.request(id)).thenReturn(request);
	}

	@Test
	void 최초호출_이후_2초_4초_8초_재시도하고_FAILED_DLQ_처리한다() throws Exception {
		when(transport.submit(request)).thenThrow(new AgentException(false));
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		InOrder order = inOrder(transport, delay, dispatches);
		order.verify(transport).mode();
		order.verify(dispatches).claim(id, "LIVE");
		for (int i = 0; i < 4; i++) {
			order.verify(dispatches).terminal(id);
			order.verify(dispatches).attempted(id);
			order.verify(transport).submit(request);
			if (i < 3) order.verify(delay).sleep(2000L << i);
		}
		order.verify(dispatches).exhausted(id);
		verify(transport, times(4)).submit(request);
		verifyNoMoreInteractions(delay);
	}

	@Test
	void 재시도에서_접수되면_추가_POST하지_않는다() throws Exception {
		when(transport.submit(request)).thenThrow(new AgentException(false)).thenReturn(null);
		listener.receive(id.toString());
		verify(transport, times(2)).submit(request);
		verify(delay).sleep(2000);
		verify(dispatches).finish(id, AnalysisDispatch.State.ACCEPTED);
		verify(dispatches, never()).exhausted(id);
	}

	@Test
	void 접수불명은_다시_POST하지_않고_폴링으로_넘긴다() {
		when(transport.submit(request)).thenThrow(new AgentException(true));
		listener.receive(id.toString());
		verify(transport).submit(request);
		verify(dispatches).finish(id, AnalysisDispatch.State.UNCERTAIN);
		verifyNoInteractions(delay);
	}

	@Test
	void 이미_접수중이거나_접수된_중복메시지는_POST하지_않는다() {
		when(dispatches.claim(id, "LIVE")).thenReturn(false);
		when(dispatches.state(id)).thenReturn(AnalysisDispatch.State.ACCEPTED);
		listener.receive(id.toString());
		verify(transport, never()).submit(any());
		verifyNoInteractions(analyses, delay);
	}

	@Test
	void 이미_최종실패한_메시지는_POST없이_DLQ로_보낸다() {
		when(dispatches.claim(id, "LIVE")).thenReturn(false);
		when(dispatches.state(id)).thenReturn(AnalysisDispatch.State.EXHAUSTED);
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verify(transport, never()).submit(any());
	}

	@Test
	void 대기중_종료된_작업은_POST하지_않는다() {
		when(dispatches.terminal(id)).thenReturn(true);
		listener.receive(id.toString());
		verify(transport, never()).submit(any());
	}
	@Test
	void 알수없는_작업번호는_무한재전달하지_않고_DLQ로_보낸다() {
		when(dispatches.claim(id, "LIVE")).thenThrow(new java.util.NoSuchElementException());
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verify(transport, never()).submit(any());
	}

	@Test
	void 잘못된_작업번호는_DLQ로_보낸다() {
		assertThatThrownBy(() -> listener.receive("invalid"))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verify(transport, never()).submit(any());
	}

	@Test
	void claim_DB장애는_2초_4초_8초_대기후_DLQ로_종료한다() throws Exception {
		when(dispatches.claim(id, "LIVE"))
			.thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"));
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verify(dispatches, times(4)).claim(id, "LIVE");
		InOrder order = inOrder(delay);
		order.verify(delay).sleep(2000);
		order.verify(delay).sleep(4000);
		order.verify(delay).sleep(8000);
		verifyNoMoreInteractions(delay);
		verify(transport, never()).submit(any());
	}

	@Test
	void 요청조회_DB장애가_복구되면_claim과_POST를_반복하지_않는다() throws Exception {
		when(analyses.request(id))
			.thenThrow(new org.springframework.transaction.CannotCreateTransactionException("DB down"))
			.thenReturn(request);
		listener.receive(id.toString());
		verify(dispatches).claim(id, "LIVE");
		verify(analyses, times(2)).request(id);
		verify(transport).submit(request);
		verify(delay).sleep(2000);
	}

	@Test
	void terminal에서_작업이_없으면_재전달하지_않고_DLQ로_보낸다() {
		when(dispatches.terminal(id)).thenThrow(new java.util.NoSuchElementException());
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verifyNoInteractions(delay);
		verify(transport, never()).submit(any());
	}

	@Test
	void 요청이_없어도_리스너_바깥으로_재큐잉_예외를_흘리지_않는다() {
		when(analyses.request(id)).thenThrow(new com.mcpcapstone.meettoticket.common.error.BusinessException(
			com.mcpcapstone.meettoticket.common.error.ErrorCode.TASK_NOT_FOUND));
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verifyNoInteractions(delay);
		verify(transport, never()).submit(any());
	}

	@Test
	void 접수후_DB저장실패는_DB만_재시도하고_POST하지_않는다() throws Exception {
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"))
			.when(dispatches).finish(id, AnalysisDispatch.State.ACCEPTED);
		assertThatThrownBy(() -> listener.receive(id.toString()))
			.isInstanceOf(AmqpRejectAndDontRequeueException.class);
		verify(transport).submit(request);
		verify(dispatches, times(4)).finish(id, AnalysisDispatch.State.ACCEPTED);
		verify(dispatches, never()).finish(id, AnalysisDispatch.State.UNCERTAIN);
		verify(delay).sleep(2000);
		verify(delay).sleep(4000);
		verify(delay).sleep(8000);
	}

	@Test
	void 접수후_DB저장이_복구되면_POST한번으로_완료한다() throws Exception {
		doThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"))
			.doNothing().when(dispatches).finish(id, AnalysisDispatch.State.ACCEPTED);
		listener.receive(id.toString());
		verify(transport).submit(request);
		verify(dispatches, times(2)).finish(id, AnalysisDispatch.State.ACCEPTED);
		verify(delay).sleep(2000);
	}

	@Test
	void DB재시도_중단시_인터럽트를_보존하고_DLQ로_보낸다() throws Exception {
		when(dispatches.claim(id, "LIVE"))
			.thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB down"));
		doThrow(new InterruptedException()).when(delay).sleep(2000);
		try {
			assertThatThrownBy(() -> listener.receive(id.toString()))
				.isInstanceOf(AmqpRejectAndDontRequeueException.class);
			org.assertj.core.api.Assertions.assertThat(Thread.currentThread().isInterrupted()).isTrue();
			verify(dispatches).claim(id, "LIVE");
			verify(transport, never()).submit(any());
		} finally { Thread.interrupted(); }
	}

}
