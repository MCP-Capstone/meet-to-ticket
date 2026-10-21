package com.mcpcapstone.meettoticket.queue;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.analysis.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;
import java.util.UUID;

class QueuedAgentGatewayTest {
	AgentGateway transport = mock(AgentGateway.class);
	AnalysisDispatchStore dispatches = mock(AnalysisDispatchStore.class);
	AnalysisTaskPublisher publisher = mock(AnalysisTaskPublisher.class);
	QueuedAgentGateway gateway = new QueuedAgentGateway(transport, dispatches, publisher);
	UUID id = UUID.randomUUID();

	@Test
	void Agent호출없이_커밋후_작업번호만_발행한다() {
		when(transport.mode()).thenReturn("LIVE");
		TransactionSynchronizationManager.initSynchronization();
		try {
			var request = new AgentContracts.StartRequest(id, null, null, List.of());
			assertThat(gateway.submit(request).status()).isEqualTo(TaskStatus.QUEUED);
			verify(dispatches).enqueue(id, "LIVE");
			verify(publisher, never()).publish(id);
			TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
			verify(publisher).publish(id);
			verify(transport, never()).submit(any());
		} finally { TransactionSynchronizationManager.clearSynchronization(); }
	}

	@Test
	void 큐대기와_접수중에는_Agent조회도_하지_않는다() {
		for (var state : List.of(AnalysisDispatch.State.QUEUED, AnalysisDispatch.State.SUBMITTING)) {
			when(dispatches.state(id)).thenReturn(state);
			assertThatThrownBy(() -> gateway.status(id)).isInstanceOfSatisfying(AgentException.class,
				e -> assertThat(e.uncertain()).isTrue());
		}
		verify(transport, never()).status(any());
	}

	@Test
	void 접수불명과_기존작업은_기존_조회로_넘긴다() {
		when(dispatches.state(id)).thenReturn(AnalysisDispatch.State.UNCERTAIN).thenReturn(null);
		gateway.status(id);
		gateway.status(id);
		verify(transport, times(2)).status(id);
	}
}
