package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.*;
import com.mcpcapstone.meettoticket.common.error.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalysisCoordinator {
    private final AnalysisStore store;
    private final AgentGateway gateway;
    private final AgentProperties properties;

    public Started start(Long meetingId, Long userId) {
        var request = store.create(meetingId, userId, gateway.mode());
        try {
            gateway.submit(request);
        } catch (AgentException e) {
            if (!e.uncertain()) {
                store.fail(request.taskId(), "AGENT_ACCEPTANCE_FAILED: Agent 실행 접수를 확인하지 못했습니다.");
                throw new BusinessException(ErrorCode.AGENT_ACCEPTANCE_FAILED);
            }
            log.warn(
                    "Agent acceptance uncertain; polling existing task {} without resubmission",
                    request.taskId());
        }
        return new Started(request.taskId(), meetingId, TaskStatus.QUEUED, gateway.mode());
    }

    // HTTP/LLM을 기다리는 동안 DB 트랜잭션을 유지하지 않는다.
    public synchronized void pollPending() {
        for (var task : store.pending()) {
            try {
                if (!gateway.mode().equals(task.executionMode())) {
                    store.fail(task.taskId(), "AGENT_MODE_CHANGED: 실행 모드가 변경돼 기존 작업을 재실행하지 않았습니다.");
                    continue;
                }
                if (task.createdAt()
                        .plus(properties.getTaskTimeout())
                        .isBefore(OffsetDateTime.now())) {
                    store.fail(
                            task.taskId(),
                            "AGENT_TIMEOUT: 제한 시간 내 결과를 확인하지 못했습니다. 자동 재발행하지 않았습니다.");
                    continue;
                }
                store.apply(task.taskId(), gateway.status(task.taskId()));
            } catch (AgentException e) {
                if (!e.uncertain())
                    store.fail(
                            task.taskId(),
                            "AGENT_TASK_UNAVAILABLE: Agent에서 기존 실행을 조회하지 못했습니다. 새 실행을 만들지 않았습니다.");
            } catch (RuntimeException e) {
                log.warn(
                        "Invalid Agent result for task {} ({})",
                        task.taskId(),
                        e.getClass().getSimpleName());
                store.fail(
                        task.taskId(),
                        "INVALID_AGENT_RESULT: Agent 응답 검증·저장에 실패했습니다. GitHub 반영 여부를 확인하기 전 재발행하지"
                                + " 마세요.");
            }
        }
    }

    public record Started(UUID taskId, Long meetingId, TaskStatus status, String executionMode) {}
}
