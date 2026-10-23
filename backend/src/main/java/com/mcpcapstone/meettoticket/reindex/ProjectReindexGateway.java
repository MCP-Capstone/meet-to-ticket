package com.mcpcapstone.meettoticket.reindex;

import java.time.Instant;

/** Backend가 Agent에 위임하는 프로젝트 단위 RAG 색인 작업. */
public interface ProjectReindexGateway {
    String start(String githubOwner, String githubRepo);

    AgentStatus status(String jobId);

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
