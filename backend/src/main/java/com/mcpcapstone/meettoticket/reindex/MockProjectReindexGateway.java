package com.mcpcapstone.meettoticket.reindex;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** 화면 개발용: 외부 GitHub/RAG 연결 없이 즉시 성공 결과를 돌려준다. */
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockProjectReindexGateway implements ProjectReindexGateway {
    @Override
    public String start(String githubOwner, String githubRepo) {
        return UUID.randomUUID().toString();
    }

    @Override
    public AgentStatus status(String jobId) {
        return new AgentStatus(ReindexStatus.SUCCEEDED, null, "재색인이 완료되었습니다.", 0, 0, 0, 0, Instant.now());
    }

    @Override
    public void dropIndex(String githubOwner, String githubRepo) {
    }
}
