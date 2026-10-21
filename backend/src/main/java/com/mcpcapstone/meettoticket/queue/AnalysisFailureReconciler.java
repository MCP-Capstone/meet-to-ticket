package com.mcpcapstone.meettoticket.queue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** DLQ는 그대로 보존하고, 실패 기록용 복사본만 DB 커밋 후 ACK한다. */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.analysis.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisFailureReconciler {
	private final RabbitTemplate rabbit;
	private final AnalysisDispatchStore dispatches;

	@Scheduled(fixedDelayString = "${app.analysis.failure-reconcile-interval-ms:5000}")
	public void reconcile() {
		// 한 번에 최대 20건. DB 장애 시 첫 실패에서 멈추고 다음 주기에 재시도한다.
		for (int i = 0; i < 20; i++) {
			Boolean processed = rabbit.execute(channel -> {
				var delivery = channel.basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false);
				if (delivery == null) return false;
				long tag = delivery.getEnvelope().getDeliveryTag();
				UUID id;
				try { id = UUID.fromString(new String(delivery.getBody(), StandardCharsets.UTF_8)); }
				catch (IllegalArgumentException e) {
					channel.basicAck(tag, false); // 원본은 DLQ에 보존돼 있다.
					return true;
				}
				try {
					dispatches.failFromDeadLetter(id);
				} catch (RuntimeException e) {
					channel.basicNack(tag, false, true);
					log.warn("Analysis failure update deferred taskId={} until next reconciliation ({})",
						id, e.getClass().getSimpleName());
					return false;
				}
				channel.basicAck(tag, false);
				return true;
			});
			if (!Boolean.TRUE.equals(processed)) return;
		}
	}
}
