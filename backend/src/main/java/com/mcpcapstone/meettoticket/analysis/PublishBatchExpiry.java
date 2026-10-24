package com.mcpcapstone.meettoticket.analysis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

// 결과 보고가 오지 않는 일괄 발행 묶음을 기한이 지나면 끝내 티켓이 발행 중에 묶이지 않게 한다.
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.analysis.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class PublishBatchExpiry {
    private final PublishService publish;

    // Agent 발행 마감(PUBLISH_DEADLINE_SECONDS, 기본 10분)보다 길게 둔다. 정상 실행은 마감 안에 보고를 받는다 (#302)
    @Value("${app.publish.result-timeout:15m}")
    private Duration timeout;

    // 접수 직후 Agent가 묶음을 등록하기 전의 404를 잃은 묶음으로 보지 않도록 기다리는 시간
    @Value("${app.publish.lost-grace:1m}")
    private Duration lostGrace;

    @Scheduled(fixedDelayString = "${app.publish.poll-interval-ms:5000}")
    public void reconcile() {
        try {
            publish.reconcileRunning(lostGrace);
        } catch (RuntimeException e) {
            log.warn("일괄 발행 결과 조회 실패, 다음 주기에 다시 시도: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${app.publish.expire-interval-ms:60000}")
    public void expire() {
        try {
            publish.expireStale(timeout);
        } catch (RuntimeException e) {
            log.warn("일괄 발행 기한 정리 실패, 다음 주기에 다시 시도: {}", e.getMessage());
        }
    }
}
