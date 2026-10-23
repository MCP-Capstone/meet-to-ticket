package com.mcpcapstone.meettoticket.reindex;

import java.time.Instant;

/** Backend가 Agent에 위임하는 프로젝트 단위 RAG 색인 작업. */
public interface ProjectReindexGateway {
    String start(String githubOwner, String githubRepo);

    /** @throws JobNotFoundException Agent가 해당 작업을 모를 때(재시작 등). 일시 오류와 구분한다. */
    AgentStatus status(String jobId);

    class JobNotFoundException extends RuntimeException {
        public JobNotFoundException(String jobId) {
            super("Agent에 재색인 작업이 없습니다: " + jobId);
        }
    }

    record AgentStatus(
            ReindexStatus status,
            String errorMessage,
            String progressMessage,
            Integer indexed,
            Integer skipped,
            Integer total,
            Integer indexCount,
            Instant lastSyncedAt) {}
}
