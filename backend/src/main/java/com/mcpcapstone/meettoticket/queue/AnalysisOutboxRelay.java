package com.mcpcapstone.meettoticket.queue;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.analysis.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class AnalysisOutboxRelay {
	private final AnalysisDispatchStore dispatches;
	private final AnalysisTaskPublisher publisher;

	@Scheduled(fixedDelayString = "${app.analysis.outbox-interval-ms:5000}")
	public void publishPending() {
		dispatches.unpublished().forEach(publisher::publish);
	}
}
