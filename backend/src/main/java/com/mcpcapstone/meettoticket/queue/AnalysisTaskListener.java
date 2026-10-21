package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.agent.AgentException;
import com.mcpcapstone.meettoticket.agent.AgentGateway;
import com.mcpcapstone.meettoticket.analysis.AnalysisStore;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AnalysisTaskListener {
	private final AgentGateway transport;
	private final AnalysisStore analyses;
	private final AnalysisDispatchStore dispatches;
	private final RetryDelay delay;
	private final QueueDatabaseRetry database;

	public AnalysisTaskListener(@Qualifier("agentTransport") AgentGateway transport,
			AnalysisStore analyses, AnalysisDispatchStore dispatches, RetryDelay delay) {
		this.transport = transport;
		this.analyses = analyses;
		this.dispatches = dispatches;
		this.delay = delay;
		this.database = new QueueDatabaseRetry(delay);
	}

	@RabbitListener(id = "analysisTaskListener", queues = AnalysisQueueConfiguration.QUEUE,
		autoStartup = "${app.analysis.queue-listener-enabled:true}", concurrency = "1")
	public void receive(String taskId) {
		try {
			process(UUID.fromString(taskId));
		} catch (AmqpRejectAndDontRequeueException e) {
			throw e;
		} catch (RuntimeException e) {
			// claim/request/terminal/attempted/finish의 예외 모두 이 경계를 통과한다.
			// DB가 계속 꺼져 있어도 무한 requeue하지 않는다. 실패 기록은 별도 큐에서 복구한다.
			log.warn("Analysis queue processing failed taskId={}; rejecting to DLQ ({})",
				taskId, e.getClass().getSimpleName());
			throw new AmqpRejectAndDontRequeueException("Queue processing failed taskId=" + taskId, e);
		}
	}

	private void process(UUID id) {
		boolean claimed = database.call(id, "claim", () -> dispatches.claim(id, transport.mode()));
		if (!claimed) {
			if (database.call(id, "state", () -> dispatches.state(id)) == AnalysisDispatch.State.EXHAUSTED)
				throw deadLetter(id);
			log.info("Analysis duplicate/terminal delivery skipped taskId={}; stale claims recover via polling", id);
			return;
		}
		AgentContracts.StartRequest request = database.call(id, "request", () -> analyses.request(id));
		for (int attempt = 1; attempt <= 4; attempt++) {
			if (database.call(id, "terminal", () -> dispatches.terminal(id))) return;
			database.run(id, "attempted", () -> dispatches.attempted(id));
			try {
				transport.submit(request);
			} catch (AgentException e) {
				if (e.uncertain()) {
					uncertain(id);
					return;
				}
				if (attempt == 4) {
					database.run(id, "exhausted", () -> dispatches.exhausted(id));
					log.warn("Agent acceptance exhausted taskId={} attempts=4; FAILED -> DLQ", id);
					throw deadLetter(id);
				}
				long millis = 2000L << (attempt - 1);
				log.warn("Agent acceptance retry taskId={} retry={}/3 delayMs={}", id, attempt, millis);
				try { delay.sleep(millis); }
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new AmqpRejectAndDontRequeueException("Agent retry interrupted taskId=" + id, interrupted);
				}
				continue;
			} catch (RuntimeException e) {
				// POST 응답 유실·예상 밖 오류는 재POST 없이 조회로 확인한다.
				uncertain(id);
				return;
			}
			// POST가 성공한 뒤 DB가 실패해도 이 DB 호출만 재시도한다.
			database.run(id, "accepted", () -> dispatches.finish(id, AnalysisDispatch.State.ACCEPTED));
			log.info("Agent accepted taskId={} attempt={}", id, attempt);
			return;
		}
	}

	private void uncertain(UUID id) {
		database.run(id, "uncertain", () -> dispatches.finish(id, AnalysisDispatch.State.UNCERTAIN));
		log.warn("Agent acceptance uncertain taskId={}; polling without resubmission", id);
	}

	private AmqpRejectAndDontRequeueException deadLetter(UUID id) {
		return new AmqpRejectAndDontRequeueException("Agent acceptance failed taskId=" + id);
	}
}
