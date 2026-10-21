package com.mcpcapstone.meettoticket.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.GetResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;

class AnalysisFailureReconcilerTest {
	RabbitTemplate rabbit = mock(RabbitTemplate.class);
	Channel channel = mock(Channel.class);
	AnalysisDispatchStore dispatches = mock(AnalysisDispatchStore.class);
	AnalysisFailureReconciler reconciler = new AnalysisFailureReconciler(rabbit, dispatches);
	UUID id = UUID.randomUUID();

	@BeforeEach
	void setup() {
		doAnswer(invocation -> {
			ChannelCallback<?> callback = invocation.getArgument(0);
			return callback.doInRabbit(channel);
		}).when(rabbit).execute(any());
	}

	private GetResponse delivery(String body) {
		return new GetResponse(new Envelope(7L, false, AnalysisQueueConfiguration.DLX,
			AnalysisQueueConfiguration.DEAD_KEY), new AMQP.BasicProperties(),
			body.getBytes(StandardCharsets.UTF_8), 0);
	}

	@Test
	void DB커밋_이후에만_실패기록_메시지를_ACK한다() throws Exception {
		when(channel.basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false))
			.thenReturn(delivery(id.toString())).thenReturn(null);
		reconciler.reconcile();
		InOrder order = inOrder(dispatches, channel);
		order.verify(channel).basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false);
		order.verify(dispatches).failFromDeadLetter(id);
		order.verify(channel).basicAck(7L, false);
		verify(channel, never()).basicGet(AnalysisQueueConfiguration.DLQ, false);
	}

	@Test
	void DB장애시_한번만_시도하고_다음주기까지_보관후_복구한다() throws Exception {
		when(channel.basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false))
			.thenReturn(delivery(id.toString()));
		doThrow(new DataAccessResourceFailureException("DB down"))
			.when(dispatches).failFromDeadLetter(id);
		reconciler.reconcile();
		verify(channel).basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false);
		verify(channel).basicNack(7L, false, true);
		verify(channel, never()).basicAck(7L, false);
		doNothing().when(dispatches).failFromDeadLetter(id);
		when(channel.basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false))
			.thenReturn(delivery(id.toString())).thenReturn(null);
		reconciler.reconcile();
		verify(dispatches, times(2)).failFromDeadLetter(id);
		verify(channel).basicAck(7L, false);
	}

	@Test
	void 잘못된_작업번호는_복사본만_제거하고_DB를_호출하지_않는다() throws Exception {
		when(channel.basicGet(AnalysisQueueConfiguration.FAILURE_UPDATES, false))
			.thenReturn(delivery("invalid")).thenReturn(null);
		reconciler.reconcile();
		verify(channel).basicAck(7L, false);
		verifyNoInteractions(dispatches);
	}

	@Test
	void 비어있는_큐에는_DB호출과_ACK를_하지_않는다() throws Exception {
		reconciler.reconcile();
		verifyNoInteractions(dispatches);
		verify(channel, never()).basicAck(anyLong(), anyBoolean());
	}
}
