package com.mcpcapstone.meettoticket.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisTaskPublisher {
	private final RabbitTemplate rabbit;
	private final AnalysisDispatchStore dispatches;

	public void publish(UUID id) {
		try {
			var properties = new MessageProperties();
			properties.setMessageId(id.toString());
			properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
			properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
			var correlation = new CorrelationData(UUID.randomUUID().toString());
			rabbit.send(AnalysisQueueConfiguration.EXCHANGE, AnalysisQueueConfiguration.ROUTING_KEY,
				new Message(id.toString().getBytes(StandardCharsets.UTF_8), properties), correlation);
			var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
			if (!confirm.ack() || correlation.getReturned() != null)
				throw new IllegalStateException("RabbitMQ publish not confirmed or unroutable");
			dispatches.published(id);
			log.info("Analysis queued taskId={}", id);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Analysis publish interrupted taskId={}; retained in outbox", id);
		} catch (Exception e) {
			// 접수된 DB 작업은 유지한다. ACK 유실에 따른 중복 발행은 영속 claim으로 막는다.
			log.warn("Analysis publish unconfirmed taskId={}; retained in outbox ({})", id,
				e.getClass().getSimpleName());
		}
	}
}
