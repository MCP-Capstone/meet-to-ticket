package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.*;
import com.mcpcapstone.meettoticket.common.*;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.meeting.*;
import com.mcpcapstone.meettoticket.project.*;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisStore {
    private final AnalysisTaskRepository tasks;
    private final TaskItemRepository items;
    private final MeetingRepository meetings;
    private final ProjectMemberRepository members;
    private final ProjectService access;
    private final JsonCodec json;
    private final AgentResultValidator validator;

    @Transactional
    public AgentContracts.StartRequest create(Long meetingId, Long userId, String mode) {
        Meeting m =
                meetings.findForAnalysis(meetingId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_NOT_FOUND));
        access.requireAccess(m.getProject().getId(), userId);
        if (tasks.existsByMeetingId(meetingId))
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_REQUESTED);
        var all = members.findByProjectIdOrderById(m.getProject().getId());
        List<Long> participantIds = new ArrayList<>();
        json.read(m.getParticipantsJson()).forEach(n -> participantIds.add(n.longValue()));
        // GitHub 계정이 없는 멤버도 분석한다. 이슈 본문에 이름만 적고 GitHub 담당자 지정은 건너뛴다 (REQ-010)
        var project = m.getProject();
        var request =
                new AgentContracts.StartRequest(
                        UUID.randomUUID(),
                        new AgentContracts.MeetingData(
                                m.getId(),
                                m.getTitle(),
                                m.getMeetingDate(),
                                "Asia/Seoul",
                                participantIds,
                                m.getOriginalFilename(),
                                m.getOriginalContent()),
                        new AgentContracts.ProjectData(
                                project.getId(),
                                project.getName(),
                                project.getGithubOwner(),
                                project.getGithubRepo()),
                        all.stream()
                                .map(
                                        x ->
                                                new AgentContracts.MemberData(
                                                        x.getId(),
                                                        x.getUser() == null
                                                                ? null
                                                                : x.getUser().getId(),
                                                        x.getName(),
                                                        x.getGithubName()))
                                .toList());
        tasks.save(
                new AnalysisTask(
                        request.taskId(),
                        m,
                        json.write(Map.of("executionMode", mode, "request", request))));
        return request;
    }

    public List<Pending> pending() {
        return tasks
                .findByStatusInOrderByCreatedAt(List.of(TaskStatus.QUEUED, TaskStatus.RUNNING))
                .stream()
                .map(t -> new Pending(t.getId(), mode(t), t.getCreatedAt()))
                .toList();
    }

    public AgentContracts.StartRequest request(UUID id) {
        return requestOf(find(id));
    }

    public TaskView status(UUID id, Long userId) {
        AnalysisTask t = authorized(id, userId);
        return view(t);
    }

    public ItemList result(UUID id, Long userId) {
        AnalysisTask t = authorized(id, userId);
        if (!t.getStatus().terminal()) throw new BusinessException(ErrorCode.TASK_NOT_FINISHED);
        List<AgentContracts.Item> result =
                items.findByTaskIdOrderById(id).stream().map(this::itemView).toList();
        return new ItemList(id, t.getStatus(), mode(t), result, summary(result));
    }

    @Transactional
    public void apply(UUID id, AgentContracts.Result result) {
        AnalysisTask t =
                tasks.findForUpdate(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
        if (t.getStatus().terminal()) return;
        boolean mock = mode(t).equals("MOCK");
        validator.validate(requestOf(t), result, mock);
        if (result.status().terminal()) {
            for (var item : result.items()) {
                var existing = items.findById(item.itemId());
                if (existing.isPresent()) {
                    if (!existing.get().getTask().getId().equals(id))
                        throw new IllegalArgumentException("다른 작업의 itemId입니다.");
                    continue;
                }
                items.save(new TaskItem(t, item, json.write(item.contentJson())));
            }
        }
        t.update(
                result.status(),
                result.stage(),
                result.finishedAt(),
                result.error() == null ? null : result.error().message());
    }

	// 진행 보고만 갱신한다. 최종 상태와 항목 저장은 기존 apply()가 담당한다.
	@Transactional
	public void updateProgress(UUID id, TaskStatus status, TaskStage stage) {
		if (status != TaskStatus.RUNNING || stage == null || stage == TaskStage.VERIFYING) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		AnalysisTask task = tasks.findForUpdate(id)
				.orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
		if (task.getStatus().terminal()) return;
		// TaskStage는 처리 순서대로 선언돼 있다. 중복·지연된 보고로 단계를 되돌리지 않는다.
		if (task.getStage() != null && stage.compareTo(task.getStage()) <= 0) return;
		task.update(TaskStatus.RUNNING, stage, task.getFinishedAt(), task.getErrorMessage());
	}

    @Transactional
    public void fail(UUID id, String message) {
        tasks.findForUpdate(id)
                .filter(t -> !t.getStatus().terminal())
                .ifPresent(
                        t ->
                                t.update(
                                        TaskStatus.FAILED,
                                        t.getStage(),
                                        OffsetDateTime.now(),
                                        message));
    }

    private AnalysisTask authorized(UUID id, Long userId) {
        AnalysisTask t = find(id);
        access.requireAccess(t.getMeeting().getProject().getId(), userId);
        return t;
    }

    private AnalysisTask find(UUID id) {
        return tasks.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.TASK_NOT_FOUND));
    }

    private AgentContracts.StartRequest requestOf(AnalysisTask t) {
        return json.convert(
                json.read(t.getInputJson()).path("request"), AgentContracts.StartRequest.class);
    }

    private String mode(AnalysisTask t) {
        return json.read(t.getInputJson()).path("executionMode").asText("LIVE");
    }

    private TaskView view(AnalysisTask t) {
        return new TaskView(
                t.getId(),
                t.getMeeting().getId(),
                t.getStatus(),
                t.getStage(),
                mode(t),
                t.getCreatedAt(),
                t.getFinishedAt(),
                t.getErrorMessage());
    }

    private AgentContracts.Item itemView(TaskItem i) {
        return new AgentContracts.Item(
                i.getId(),
                i.getWorkKey(),
                i.getTitle(),
                json.read(i.getContentJson()),
                i.getAction(),
                i.getReason(),
                i.getReviewStatus(),
                i.getPublishStatus(),
                i.getGithubIssueNumber(),
                i.getGithubIssueUrl(),
                i.getErrorMessage());
    }

    private AgentContracts.Summary summary(List<AgentContracts.Item> list) {
        int approved = 0, rejected = 0, s = 0, f = 0, u = 0, k = 0;
        for (var i : list) {
            if ("APPROVED".equals(i.reviewStatus())) approved++;
            else if ("REJECTED".equals(i.reviewStatus())) rejected++;
            switch (i.publishStatus()) {
                case "SUCCEEDED" -> s++;
                case "FAILED" -> f++;
                case "UNKNOWN" -> u++;
                case "SKIPPED" -> k++;
                default -> {}
            }
        }
        return new AgentContracts.Summary(list.size(), approved, rejected, s, f, u, k);
    }

    public record Pending(UUID taskId, String executionMode, OffsetDateTime createdAt) {}

    public record TaskView(
            @Schema(description = "분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81")
            UUID taskId,
            @Schema(description = "회의 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
            Long meetingId,
            @Schema(description = "작업 상태. COMPLETED/PARTIALLY_FAILED/FAILED에서 폴링 종료", requiredMode = Schema.RequiredMode.REQUIRED, example = "RUNNING")
            TaskStatus status,
            @Schema(description = "현재 또는 마지막 단계. QUEUED일 때 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "REVIEWING")
            TaskStage stage,
            @Schema(description = "백엔드 실행 모드", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MOCK", "LIVE"}, example = "MOCK")
            String executionMode,
            @Schema(description = "Spring 최초 접수 시각", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-22T10:31:00+09:00")
            OffsetDateTime createdAt,
            @Schema(description = "종료 시각. 진행 중 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            OffsetDateTime finishedAt,
            @Schema(description = "전체 실패 사유. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String errorMessage) {}

    public record ItemList(
            @Schema(description = "분석 UUID", requiredMode = Schema.RequiredMode.REQUIRED, example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81")
            UUID taskId,
            @Schema(description = "종료 상태", requiredMode = Schema.RequiredMode.REQUIRED, example = "COMPLETED")
            TaskStatus status,
            @Schema(description = "백엔드 실행 모드", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"MOCK", "LIVE"}, example = "MOCK")
            String executionMode,
            @Schema(description = "최종 항목. 할 일 없음은 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
            List<AgentContracts.Item> items,
            @Schema(description = "DB 항목으로 계산한 집계", requiredMode = Schema.RequiredMode.REQUIRED) AgentContracts.Summary summary) {}
}
