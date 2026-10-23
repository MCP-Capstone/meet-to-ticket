package com.mcpcapstone.meettoticket.reindex;

import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.project.ProjectService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
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

    /** start 호출과 job id 바인딩 사이에 허용하는 시간. */
    private static final Duration BIND_GRACE = Duration.ofMinutes(2);
    /** 이 시간을 넘긴 RUNNING은 정체로 보고 실패 처리한다. */
    private static final Duration MAX_RUNNING = Duration.ofHours(2);

    /** 관리자가 시작한다. 이미 실행 중이면 같은 실행 상태를 반환해 중복 색인을 막는다. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReindexData request(Long projectId, Long userId) {
        projectService.requireAdmin(projectId, userId);
        // 멈춘 RUNNING(Agent 재시작, job id 미바인딩, 장기 정체)은 먼저 정리해 재시작할 수 있게 한다.
        refresh(projectId, userId);

        record Plan(boolean claimed, String owner, String repo) {}
        Plan plan;
        try {
            // 행을 잠근 채 RUNNING 확인과 초기화를 한 트랜잭션에서 처리해 동시 요청의 이중 시작을 막는다.
            plan = tx.execute(status -> {
                Project project = projects.getReferenceById(projectId);
                ProjectReindex run = reindexes.findByProjectIdForUpdate(projectId).orElse(null);
                if (run == null) reindexes.save(new ProjectReindex(project));
                else if (run.getStatus() == ReindexStatus.RUNNING) return new Plan(false, null, null);
                else run.request();
                return new Plan(true, project.getGithubOwner(), project.getGithubRepo());
            });
        } catch (DataIntegrityViolationException e) {
            // 최초 생성이 동시에 일어나 unique 제약에 진 쪽이다. 먼저 만든 실행을 그대로 보여 준다.
            return load(projectId);
        }
        if (!plan.claimed()) return load(projectId);
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
        if (current == null || current.getStatus() != ReindexStatus.RUNNING) return load(projectId);
        Instant now = Instant.now();
        if (current.getRequestedAt().plus(MAX_RUNNING).isBefore(now)) {
            failRunning(projectId, "재색인이 제한 시간 안에 끝나지 않아 실패 처리했습니다.");
            return load(projectId);
        }
        if (current.getAgentJobId() == null) {
            // start 호출 중이면 곧 바인딩된다. 유예 시간이 지나도 없으면 백엔드가 중간에 죽은 것이다.
            if (current.getRequestedAt().plus(BIND_GRACE).isBefore(now))
                failRunning(projectId, "Agent에 재색인을 요청하지 못했습니다.");
            return load(projectId);
        }
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
        } catch (ProjectReindexGateway.JobNotFoundException e) {
            // 영구 오류다. Agent가 재시작되면 메모리의 job이 사라지므로 더 기다려도 복구되지 않는다.
            log.warn("프로젝트 {} 재색인 작업을 Agent에서 찾지 못함: {}", projectId, e.getMessage());
            failRunning(projectId, "Agent가 재시작되어 재색인 작업을 찾을 수 없습니다. 다시 시도해 주세요.");
        } catch (RuntimeException e) {
            log.warn("프로젝트 {} 재색인 상태 조회 실패: {}", projectId, e.getMessage());
            // 일시적인 네트워크 오류에는 실행 중 상태를 보존한다. 다음 폴링에서 다시 확인한다.
        }
        return load(projectId);
    }

    private void failRunning(Long projectId, String message) {
        tx.executeWithoutResult(status -> reindexes.findByProjectId(projectId)
                .filter(run -> run.getStatus() == ReindexStatus.RUNNING)
                .ifPresent(run -> run.fail(message)));
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
