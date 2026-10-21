package com.mcpcapstone.meettoticket.analysis;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.analysis.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AnalysisScheduler {
    private final AnalysisCoordinator coordinator;

    @Scheduled(fixedDelayString = "${agent.poll-interval-ms:2000}")
    public void poll() {
        coordinator.pollPending();
    }
}
