package com.mcpcapstone.meettoticket.queue;

import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

/** 재시도 범위를 개별 DB 호출로 제한해 성공했을 수도 있는 Agent POST를 반복하지 않는다. */
@RequiredArgsConstructor
@Slf4j
final class QueueDatabaseRetry {
	private final RetryDelay delay;

	<T> T call(UUID id, String operation, Supplier<T> action) {
		for (int attempt = 1; ; attempt++) {
			try {
				return action.get();
			} catch (DataAccessException | TransactionException e) {
				if (attempt == 4) throw e;
				long millis = 2000L << (attempt - 1);
				log.warn("Analysis DB retry taskId={} operation={} retry={}/3 delayMs={}",
					id, operation, attempt, millis);
				try { delay.sleep(millis); }
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new AmqpRejectAndDontRequeueException("DB retry interrupted taskId=" + id, interrupted);
				}
			}
		}
	}

	void run(UUID id, String operation, Runnable action) {
		call(id, operation, () -> { action.run(); return null; });
	}
}
