package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.analysis.AnalysisStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.NoSuchElementException;

@Component
@Slf4j
public class AnalysisTaskListener {
	private final AgentGateway transport;
	private final AnalysisStore analyses;
	private final AnalysisDispatchStore dispatches;
	private final RetryDelay delay;

	public AnalysisTaskListener(@Qualifier("agentTransport") AgentGateway transport,
			AnalysisStore analyses, AnalysisDispatchStore dispatches, RetryDelay delay) {
		this.transport = transport;
		this.analyses = analyses;
		this.dispatches = dispatches;
		this.delay = delay;
	}

	@RabbitListener(id = "analysisTaskListener", queues = AnalysisQueueConfiguration.QUEUE,
		autoStartup = "${app.analysis.queue-listener-enabled:true}", concurrency = "1")
	public void receive(String taskId) {
		UUID id;
		try { id = UUID.fromString(taskId); }
		catch (IllegalArgumentException e) {
			throw new AmqpRejectAndDontRequeueException("Invalid taskId", e);
		}
		boolean claimed;
		try { claimed = dispatches.claim(id, transport.mode()); }
		catch (NoSuchElementException e) {
			throw new AmqpRejectAndDontRequeueException("Unknown taskId=" + id, e);
		}
		if (!claimed) {
			if (dispatches.state(id) == AnalysisDispatch.State.EXHAUSTED) throw deadLetter(id);
			log.info("Analysis duplicate/terminal delivery skipped taskId={}", id);
			return;
		}
		var request = analyses.request(id);
		for (int attempt = 1; attempt <= 4; attempt++) {
			if (dispatches.terminal(id)) return;
			dispatches.attempted(id);
			try {
				transport.submit(request);
				dispatches.finish(id, AnalysisDispatch.State.ACCEPTED);
				log.info("Agent accepted taskId={} attempt={}", id, attempt);
				return;
			} catch (AgentException e) {
				if (e.uncertain()) {
					dispatches.finish(id, AnalysisDispatch.State.UNCERTAIN);
					log.warn("Agent acceptance uncertain taskId={}; polling without resubmission", id);
					return;
				}
				if (attempt == 4) {
					dispatches.exhausted(id);
					log.warn("Agent acceptance exhausted taskId={} attempts=4; FAILED -> DLQ", id);
					throw deadLetter(id);
				}
				long millis = 2000L << (attempt - 1);
				log.warn("Agent acceptance retry taskId={} retry={}/3 delayMs={}", id, attempt, millis);
				try { delay.sleep(millis); }
				catch (InterruptedException interrupted) {
					// 마지막 호출은 확실한 실패였다. 중단 시에도 자동 재POST하지 않는다.
					Thread.currentThread().interrupt();
					dispatches.exhausted(id);
					throw deadLetter(id);
				}
			} catch (AmqpRejectAndDontRequeueException e) {
				throw e;
			} catch (RuntimeException e) {
				// POST 뒤 내부 오류도 접수 불명으로 취급해 재전송하지 않는다.
				dispatches.finish(id, AnalysisDispatch.State.UNCERTAIN);
				log.warn("Agent submission interrupted taskId={}; polling without resubmission ({})",
					id, e.getClass().getSimpleName());
				return;
			}
		}
	}

	private AmqpRejectAndDontRequeueException deadLetter(UUID id) {
		return new AmqpRejectAndDontRequeueException("Agent acceptance failed taskId=" + id);
	}
}
