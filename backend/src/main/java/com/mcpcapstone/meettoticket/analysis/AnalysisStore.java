package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.*;
import com.mcpcapstone.meettoticket.common.*;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.meeting.*;
import com.mcpcapstone.meettoticket.project.*;

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
        if (mode.equals("LIVE")
                && all.stream()
                        .filter(x -> participantIds.contains(x.getId()))
                        .anyMatch(x -> x.getGithubName() == null || x.getGithubName().isBlank()))
            throw new BusinessException(ErrorCode.MEMBER_GITHUB_UNLINKED);
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
            UUID taskId,
            Long meetingId,
            TaskStatus status,
            TaskStage stage,
            String executionMode,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt,
            String errorMessage) {}

    public record ItemList(
            UUID taskId,
            TaskStatus status,
            String executionMode,
            List<AgentContracts.Item> items,
            AgentContracts.Summary summary) {}
}
