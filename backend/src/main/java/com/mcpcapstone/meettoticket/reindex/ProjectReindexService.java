package com.mcpcapstone.meettoticket.reindex;

import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.project.ProjectService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectReindexService {
    private final ProjectService projectService;
    private final ProjectRepository projects;
    private final ProjectReindexRepository reindexes;
    private final ProjectReindexGateway agent;
    private final TransactionTemplate tx;

    /** 관리자가 시작한다. 이미 실행 중이면 같은 실행 상태를 반환해 중복 색인을 막는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReindexData request(Long projectId, Long userId) {
        projectService.requireAdmin(projectId, userId);
        ProjectReindex existing = tx.execute(status -> reindexes.findByProjectId(projectId).orElse(null));
        if (existing != null && existing.getStatus() == ReindexStatus.RUNNING) return refresh(projectId, userId);

        record Plan(String owner, String repo) {}
        Plan plan = tx.execute(status -> {
            Project project = projects.getReferenceById(projectId);
            ProjectReindex run = reindexes.findByProjectId(projectId).orElseGet(() -> reindexes.save(new ProjectReindex(project)));
            if (run.getId() != null) run.request();
            return new Plan(project.getGithubOwner(), project.getGithubRepo());
        });
        try {
            String jobId = agent.start(plan.owner(), plan.repo());
            tx.executeWithoutResult(status -> reindexes.findByProjectId(projectId).ifPresent(run -> run.bindAgentJob(jobId)));
        } catch (RuntimeException e) {
            log.warn("프로젝트 {} 재색인 접수 실패: {}", projectId, e.getMessage());
            tx.executeWithoutResult(status -> reindexes.findByProjectId(projectId).ifPresent(run -> run.fail("Agent에 재색인을 요청하지 못했습니다.")));
        }
        return load(projectId);
    }

    /** 프로젝트 멤버는 상태를 볼 수 있다. RUNNING이면 Agent 상태를 최신화한다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReindexData status(Long projectId, Long userId) {
        projectService.requireAccess(projectId, userId);
        return refresh(projectId, userId);
    }

    private ReindexData refresh(Long projectId, Long userId) {
        ProjectReindex current = tx.execute(status -> reindexes.findByProjectId(projectId).orElse(null));
        if (current == null || current.getStatus() != ReindexStatus.RUNNING || current.getAgentJobId() == null)
            return load(projectId);
        try {
            ProjectReindexGateway.AgentStatus agentStatus = agent.status(current.getAgentJobId());
            if (agentStatus.status() != ReindexStatus.RUNNING) {
                tx.executeWithoutResult(status -> reindexes.findByProjectId(projectId).ifPresent(run -> {
                    if (agentStatus.status() == ReindexStatus.SUCCEEDED) run.succeed(agentStatus);
                    else run.fail(agentStatus.errorMessage());
                }));
            } else {
                tx.executeWithoutResult(status -> reindexes.findByProjectId(projectId)
                        .ifPresent(run -> run.updateProgress(agentStatus.progressMessage())));
            }
        } catch (RuntimeException e) {
            log.warn("프로젝트 {} 재색인 상태 조회 실패: {}", projectId, e.getMessage());
            // 일시적인 네트워크 오류에는 실행 중 상태를 보존한다. 다음 폴링에서 다시 확인한다.
        }
        return load(projectId);
    }

    private ReindexData load(Long projectId) {
        return tx.execute(status -> reindexes.findByProjectId(projectId).map(ReindexData::from).orElseGet(() -> ReindexData.idle(projectId)));
    }

    public record ReindexData(
            Long projectId, ReindexStatus status, Instant requestedAt, Instant finishedAt, String errorMessage,
            String progressMessage, Integer indexed, Integer skipped, Integer total, Integer indexCount, Instant lastSyncedAt) {
        static ReindexData idle(Long projectId) {
            return new ReindexData(projectId, null, null, null, null, null, null, null, null, null, null);
        }

        static ReindexData from(ProjectReindex run) {
            return new ReindexData(run.getProject().getId(), run.getStatus(), run.getRequestedAt(), run.getFinishedAt(),
                    run.getErrorMessage(), run.getProgressMessage(), run.getIndexed(), run.getSkipped(), run.getTotal(), run.getIndexCount(), run.getLastSyncedAt());
        }
    }
}
