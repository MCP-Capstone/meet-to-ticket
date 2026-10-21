package com.mcpcapstone.meettoticket.agent;

import com.mcpcapstone.meettoticket.analysis.*;

import tools.jackson.databind.JsonNode;

import java.time.*;
import java.util.*;

public final class AgentContracts {
    private AgentContracts() {}

    public record StartRequest(
            UUID taskId,
            MeetingData meeting,
            ProjectData project,
            List<MemberData> projectMembers) {}

    public record MeetingData(
            Long meetingId,
            String title,
            LocalDate meetingDate,
            String timezone,
            List<Long> participantIds,
            String originalFilename,
            String originalContent) {}

    public record ProjectData(Long projectId, String name, String githubOwner, String githubRepo) {}

    public record MemberData(Long projectMemberId, Long userId, String name, String githubName) {}

    public record Accepted(
            UUID taskId,
            TaskStatus status,
            TaskStage stage,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt) {}

    public record Result(
            UUID taskId,
            Long meetingId,
            TaskStatus status,
            TaskStage stage,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt,
            List<Item> items,
            Summary summary,
            AgentError error) {}

    public record Item(
            UUID itemId,
            String workKey,
            String title,
            JsonNode contentJson,
            String action,
            String reason,
            String reviewStatus,
            String publishStatus,
            Integer githubIssueNumber,
            String githubIssueUrl,
            String errorMessage) {}

    public record Summary(
            int total,
            int approved,
            int rejected,
            int succeeded,
            int failed,
            int unknown,
            int skipped) {}

    public record AgentError(String code, String message) {}
}
