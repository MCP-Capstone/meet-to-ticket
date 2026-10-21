package com.mcpcapstone.meettoticket.queue;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import java.util.UUID;

class AnalysisTaskPublisherTest {
	RabbitTemplate rabbit = mock(RabbitTemplate.class);
	AnalysisDispatchStore dispatches = mock(AnalysisDispatchStore.class);
	AnalysisTaskPublisher publisher = new AnalysisTaskPublisher(rabbit, dispatches);
	UUID id = UUID.randomUUID();

	@Test
	void 브로커가_확인한_발행만_완료로_기록한다() {
		doAnswer(call -> {
			CorrelationData data = call.getArgument(3);
			data.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		publisher.publish(id);
		verify(dispatches).published(id);
	}

	@Test
	void 라우팅되지_않은_메시지는_outbox에_남긴다() {
		doAnswer(call -> {
			CorrelationData data = call.getArgument(3);
			data.setReturned(new ReturnedMessage(call.getArgument(2), 312, "NO_ROUTE", "analysis.tasks", "analysis.submit"));
			data.getFuture().complete(new CorrelationData.Confirm(true, null));
			return null;
		}).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		publisher.publish(id);
		verify(dispatches, never()).published(id);
	}

	@Test
	void 브로커_연결실패는_outbox에_남겨_나중에_발행한다() {
		doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException()))
			.when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
		publisher.publish(id);
		verify(dispatches, never()).published(id);
	}
}
