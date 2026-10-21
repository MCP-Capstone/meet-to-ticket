package com.mcpcapstone.meettoticket.agent;

import com.mcpcapstone.meettoticket.analysis.*;

import tools.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.media.Schema;

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
            @Schema(description = "항목 UUID. Agent taskItemId를 변환한 값", requiredMode = Schema.RequiredMode.REQUIRED, example = "3c510e28-c776-43ce-85cc-6c90693d31f5")
            UUID itemId,
            @Schema(description = "원문 업무 키. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String workKey,
            @Schema(description = "업무 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 빈 결과 오류 수정")
            String title,
            @Schema(description = "업무 상세·담당자·기한·근거·검수·발행 JSON. 내용은 Agent 응답과 실행 모드에 따라 다름", requiredMode = Schema.RequiredMode.REQUIRED, type = "object", implementation = Object.class)
            JsonNode contentJson,
            @Schema(description = "수행 작업 유형", requiredMode = Schema.RequiredMode.REQUIRED, example = "CREATE")
            String action,
            @Schema(description = "업무 처리 사유. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String reason,
            @Schema(description = "Spring에서 정규화한 검수 결과", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"APPROVED", "REJECTED"}, example = "APPROVED")
            String reviewStatus,
            @Schema(description = "생성 성공·실패·결과 불명·반려 제외·발행 생략 상태", requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"SUCCEEDED", "FAILED", "UNKNOWN", "BLOCKED", "SKIPPED"}, example = "SKIPPED")
            String publishStatus,
            @Schema(description = "실제 생성된 이슈 번호. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            Integer githubIssueNumber,
            @Schema(description = "실제 이슈 URL. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String githubIssueUrl,
            @Schema(description = "항목별 발행 실패·결과 불명 사유. 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String errorMessage) {}

    public record Summary(
            @Schema(description = "최종 항목 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int total,
            @Schema(description = "검수 통과 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int approved,
            @Schema(description = "검수 반려 수. failed에 포함하지 않음", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int rejected,
            @Schema(description = "이슈 생성 성공 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int succeeded,
            @Schema(description = "명확한 이슈 생성 실패 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int failed,
            @Schema(description = "이슈 생성 여부 확인 불가 수", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int unknown,
            @Schema(description = "발행 생략 수. 기본 demo에서 사용", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
            int skipped) {}

    public record AgentError(String code, String message) {}
}
