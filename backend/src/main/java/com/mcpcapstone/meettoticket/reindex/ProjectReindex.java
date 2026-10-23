package com.mcpcapstone.meettoticket.reindex;

import com.mcpcapstone.meettoticket.project.Project;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "project_reindexes", uniqueConstraints = @UniqueConstraint(columnNames = "project_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectReindex {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_reindex_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, unique = true)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReindexStatus status;

    @Column(name = "agent_job_id", length = 64)
    private String agentJobId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "progress_message", length = 500)
    private String progressMessage;

    private Integer indexed;
    private Integer skipped;
    private Integer total;

    @Column(name = "index_count")
    private Integer indexCount;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    public ProjectReindex(Project project) {
        this.project = project;
        request();
    }

    public void request() {
        status = ReindexStatus.RUNNING;
        agentJobId = null;
        requestedAt = Instant.now();
        finishedAt = null;
        errorMessage = null;
        progressMessage = "재색인 작업을 접수했습니다.";
        indexed = skipped = total = indexCount = null;
        lastSyncedAt = null;
    }

    public void bindAgentJob(String jobId) {
        this.agentJobId = jobId;
    }

    public void succeed(ProjectReindexGateway.AgentStatus agent) {
        status = ReindexStatus.SUCCEEDED;
        finishedAt = Instant.now();
        errorMessage = null;
        progressMessage = "재색인이 완료되었습니다.";
        indexed = agent.indexed();
        skipped = agent.skipped();
        total = agent.total();
        indexCount = agent.indexCount();
        lastSyncedAt = agent.lastSyncedAt();
    }

    public void fail(String message) {
        status = ReindexStatus.FAILED;
        finishedAt = Instant.now();
        errorMessage = message == null || message.isBlank() ? "재색인에 실패했습니다." : message;
        progressMessage = null;
    }

    public void updateProgress(String message) {
        if (status == ReindexStatus.RUNNING && message != null && !message.isBlank())
            progressMessage = message;
    }
}
