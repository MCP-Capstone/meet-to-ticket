package com.mcpcapstone.meettoticket.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 멈춘 발행 기록을 주기적으로 정리한다 (#263 PR2). 판단은 PublicationRecovery가 한다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.analysis.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class PublicationRecoveryWorker {
    private final PublicationRecovery recovery;

    @Value("${app.publish.recovery.enabled:true}")
    private boolean enabled;

    @Value("${app.publish.recovery.stale-after:10m}")
    private Duration staleAfter;

    @Value("${app.publish.recovery.batch-size:100}")
    private int batchSize;

    @Scheduled(
            initialDelayString = "${app.publish.recovery.interval-ms:300000}",
            fixedDelayString = "${app.publish.recovery.interval-ms:300000}")
    public void recover() {
        if (!enabled) return;
        try {
            recovery.recoverStale(staleAfter, batchSize);
        } catch (RuntimeException e) {
            log.warn("멈춘 발행 기록 정리 실패, 다음 주기에 다시 시도: {}", e.getMessage());
        }
    }
}
