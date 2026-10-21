package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.agent.AgentGateway;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
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

	@Bean
	public Declarables analysisQueues() {
		var exchange = new DirectExchange(EXCHANGE, true, false);
		var deadExchange = new DirectExchange(DLX, true, false);
		var queue = QueueBuilder.durable(QUEUE)
			.deadLetterExchange(DLX).deadLetterRoutingKey(DEAD_KEY).build();
		var deadQueue = QueueBuilder.durable(DLQ).build();
		return new Declarables(exchange, deadExchange, queue, deadQueue,
			BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY),
			BindingBuilder.bind(deadQueue).to(deadExchange).with(DEAD_KEY));
	}

	@Bean
	@Primary
	public AgentGateway queuedAgentGateway(@Qualifier("agentTransport") AgentGateway transport,
			AnalysisDispatchStore dispatches, AnalysisTaskPublisher publisher) {
		return new QueuedAgentGateway(transport, dispatches, publisher);
	}

	@Bean
	public RetryDelay analysisRetryDelay() { return Thread::sleep; }
}
