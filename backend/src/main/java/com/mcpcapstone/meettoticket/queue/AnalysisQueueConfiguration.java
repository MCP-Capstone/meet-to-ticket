package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.agent.AgentGateway;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class AnalysisQueueConfiguration {
	public static final String EXCHANGE = "analysis.tasks";
	public static final String QUEUE = "analysis.tasks.queue";
	public static final String ROUTING_KEY = "analysis.submit";
	public static final String DLX = "analysis.tasks.dlx";
	public static final String DLQ = "analysis.tasks.dlq";
	public static final String DEAD_KEY = "analysis.failed";
	public static final String FAILURE_UPDATES = "analysis.tasks.failure-updates";

	@Bean
	public Declarables analysisQueues() {
		var exchange = new DirectExchange(EXCHANGE, true, false);
		var deadExchange = new DirectExchange(DLX, true, false);
		var queue = QueueBuilder.durable(QUEUE)
			.deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build();
		var deadQueue = QueueBuilder.durable(DLQ).build();
		// DLQ 원본은 증빙·운영 확인용으로 남기고, 복사본으로 DB 실패 기록을 복구한다.
		var failureUpdates = QueueBuilder.durable(FAILURE_UPDATES).build();
		return new Declarables(exchange, deadExchange, queue, deadQueue, failureUpdates,
			BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
			BindingBuilder.bind(deadQueue).to(deadExchange).with(DEAD_KEY),
			BindingBuilder.bind(failureUpdates).to(deadExchange).with(DEAD_KEY));
	}

	@Bean
	@Primary
	public AgentGateway queuedAgentGateway(@Qualifier("agentTransport") AgentGateway transport,
			AnalysisDispatchStore dispatches, AnalysisTaskPublisher publisher,
			@Value("${app.analysis.submitting-timeout:2m}") Duration submittingTimeout) {
		if (submittingTimeout.isNegative() || submittingTimeout.isZero())
			throw new IllegalArgumentException("submitting-timeout must be positive");
		return new QueuedAgentGateway(transport, dispatches, publisher, submittingTimeout);
	}

	@Bean
	public RetryDelay analysisRetryDelay() { return Thread::sleep; }
}
