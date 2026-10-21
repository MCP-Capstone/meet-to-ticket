package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.analysis.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

@RequiredArgsConstructor
public class QueuedAgentGateway implements AgentGateway {
	private final AgentGateway transport;
	private final AnalysisDispatchStore dispatches;
	private final AnalysisTaskPublisher publisher;

	@Override
	public String mode() { return transport.mode(); }

	@Override
	public AgentContracts.Accepted submit(AgentContracts.StartRequest request) {
		dispatches.enqueue(request.taskId(), mode());
		// 작업과 outbox가 함께 커밋된 뒤 발행한다. 브로커 장애는 outbox relay가 복구한다.
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() { publisher.publish(request.taskId()); }
			});
		} else {
			publisher.publish(request.taskId());
		}
		return new AgentContracts.Accepted(request.taskId(), TaskStatus.QUEUED, null, null, null);
	}

	@Override
	public AgentContracts.Result status(UUID id) {
		var state = dispatches.state(id);
		// 큐 대기/재시도 중 GET /runs의 404로 기존 폴링이 작업을 실패시키지 않게 한다.
		if (state == AnalysisDispatch.State.QUEUED || state == AnalysisDispatch.State.SUBMITTING
				|| state == AnalysisDispatch.State.EXHAUSTED) throw new AgentException(true);
		return transport.status(id);
	}
}
