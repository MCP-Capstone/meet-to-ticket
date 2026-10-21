package com.mcpcapstone.meettoticket.agent;

import com.mcpcapstone.meettoticket.analysis.TaskStatus;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.*;
import java.util.*;

@Component
public class AgentResultValidator {
    public void validate(
            AgentContracts.StartRequest request, AgentContracts.Result result, boolean mock) {
        require(
                result != null
                        && request.taskId().equals(result.taskId())
                        // Agent(#52) 응답에는 회의 ID가 없다.
                        && (!mock && result.meetingId() == null
                                || request.meeting().meetingId().equals(result.meetingId())),
                "작업 ID와 회의 ID 불일치");
        require(
                result.status() != null && result.items() != null && result.createdAt() != null,
                "상태·항목·접수 시각 누락");
        if (!result.status().terminal()) {
            require(
                    result.finishedAt() == null
                            && result.items().isEmpty()
                            && result.summary() == null
                            && result.error() == null,
                    "진행 중 응답에 종료 결과 포함");
            require(result.status() != TaskStatus.RUNNING || result.stage() != null, "RUNNING 단계 누락");
            return;
        }
        require(result.finishedAt() != null && result.stage() != null, "종료 시각 또는 마지막 단계 누락");
        if (!mock) {
            validateAgentRun(request, result);
            return;
        }
        Set<UUID> ids = new HashSet<>();
        int approved = 0, rejected = 0, success = 0, failed = 0, unknown = 0, skipped = 0;
        for (var item : result.items()) {
            require(
                    item != null
                            && item.itemId() != null
                            && ids.add(item.itemId())
                            && text(item.title())
                            && item.title().length() <= 255
                            && "CREATE".equals(item.action())
                            && text(item.reason()),
                    "항목 ID 중복 또는 필수 제목·action·사유 오류");
            require(item.workKey() == null || item.workKey().length() <= 100, "workKey 길이 초과");
            JsonNode c = item.contentJson();
            require(c != null && c.isObject(), "contentJson 객체 누락");
            JsonNode review = c.path("review");
            int correction = review.path("correctionCount").asInt(-1),
                    revision = c.path("revision").asInt(-1);
            require(
                    correction >= 0
                            && correction <= 2
                            && revision == correction + 1
                            && review.path("reviewedRevision").asInt(-1) == revision
                            && text(review.path("reason").asText("")),
                    "보완 횟수·검수 버전·사유 오류");
            if ("REJECTED".equals(item.reviewStatus())) {
                require(
                        "BLOCKED".equals(item.publishStatus())
                                && item.githubIssueNumber() == null
                                && item.githubIssueUrl() == null
                                && review.path("approvedPayload").isNull()
                                && !c.path("publication").path("verified").asBoolean(false),
                        "반려 항목의 발행 상태·승인 인자 오류");
                rejected++;
                continue;
            }
            require("APPROVED".equals(item.reviewStatus()), "검수 상태 오류");
            approved++;
            require(c.path("publication").path("mock").asBoolean(false), "모의 발행 표시 누락");
            switch (item.publishStatus() == null ? "" : item.publishStatus()) {
                case "SUCCEEDED" -> {
                    require(!mock, "모의 실행은 실제 발행 성공으로 기록할 수 없음");
                    require(item.githubIssueNumber() != null && item.githubIssueNumber() > 0,
                            "생성 성공의 이슈 번호 오류");
                    require(validUrl(request, item), "생성 성공의 이슈 URL 불일치");
                    require(c.path("publication").path("verified").asBoolean(false),
                            "생성 성공의 발행 확인 누락");
                    require(item.errorMessage() == null, "생성 성공에 오류 메시지 포함");
                    OffsetDateTime.parse(c.path("publication").path("publishedAt").asText());
                    success++;
                }
                case "FAILED" -> {
                    require(
                            text(item.errorMessage())
                                    && item.githubIssueNumber() == null
                                    && item.githubIssueUrl() == null
                                    && !c.path("publication").path("verified").asBoolean(false),
                            "생성 실패의 사유·번호·확인 상태 오류");
                    failed++;
                }
                case "UNKNOWN" -> {
                    require(
                            text(item.errorMessage())
                                    && !c.path("publication").path("verified").asBoolean(false),
                            "결과 불명의 사유·확인 상태 오류");
                    if (item.githubIssueNumber() != null || item.githubIssueUrl() != null)
                        require(
                                item.githubIssueNumber() != null && validUrl(request, item),
                                "결과 불명의 이슈 번호·URL 불일치");
                    unknown++;
                }
                case "SKIPPED" -> {
                    require(
                            mock
                                    && item.githubIssueNumber() == null
                                    && item.githubIssueUrl() == null
                                    && !c.path("publication").path("verified").asBoolean(false),
                            "모의 발행 생략 상태 오류");
                    skipped++;
                }
                default -> throw invalid("발행 상태 오류");
            }
        }
        var summary = result.summary();
        if (summary != null)
            require(
                    summary.equals(
                            new AgentContracts.Summary(
                                    result.items().size(),
                                    approved,
                                    rejected,
                                    success,
                                    failed,
                                    unknown,
                                    skipped)),
                    "항목 집계 불일치");
        if (result.error() != null) {
            require(
                    result.status() == TaskStatus.FAILED && text(result.error().message()),
                    "전체 오류의 상태·메시지 오류");
            return;
        }
        require(summary != null, "종료 집계 누락");
        TaskStatus expected =
                failed + unknown == 0
                        ? TaskStatus.COMPLETED
                        : success + skipped > 0 ? TaskStatus.PARTIALLY_FAILED : TaskStatus.FAILED;
        require(result.status() == expected, "집계에 따른 작업 상태 불일치");
    }

    // LIVE: Agent(#52) 계약 기준. 근거 행 번호 / 승인 인자 / 발행 확인 필드는 Agent가 주지 않으므로 보지 않는다.
    // 대신 실제 GitHub에 영향을 주는 값(이슈 번호 / URL이 프로젝트 저장소인지)과 상태 / 집계 일치는 확인한다.
    private void validateAgentRun(AgentContracts.StartRequest request, AgentContracts.Result result) {
        Set<UUID> ids = new HashSet<>();
        for (var item : result.items()) {
            require(
                    item != null
                            && item.itemId() != null
                            && ids.add(item.itemId())
                            && text(item.title())
                            && item.title().length() <= 255,
                    "항목 ID 중복 또는 제목 오류");
            require(item.workKey() == null || item.workKey().length() <= 100, "workKey 길이 초과");
            require(item.contentJson() != null && item.contentJson().isObject(), "contentJson 객체 누락");
            if ("REJECTED".equals(item.reviewStatus())) {
                require(
                        "BLOCKED".equals(item.publishStatus())
                                && item.githubIssueNumber() == null
                                && item.githubIssueUrl() == null,
                        "반려 항목의 발행 상태 오류");
                continue;
            }
            require("APPROVED".equals(item.reviewStatus()), "검수 상태 오류");
            switch (item.publishStatus() == null ? "" : item.publishStatus()) {
                case "SUCCEEDED" -> {
                    require(item.githubIssueNumber() != null && item.githubIssueNumber() > 0,
                            "생성 성공의 이슈 번호 오류");
                    require(validUrl(request, item), "생성 성공의 이슈 URL 불일치");
                }
                case "FAILED" -> require(
                        text(item.errorMessage())
                                && item.githubIssueNumber() == null
                                && item.githubIssueUrl() == null,
                        "생성 실패의 사유 / 번호 오류");
                case "UNKNOWN" -> {
                    require(text(item.errorMessage()), "결과 불명의 사유 누락");
                    if (item.githubIssueNumber() != null || item.githubIssueUrl() != null)
                        require(
                                item.githubIssueNumber() != null && validUrl(request, item),
                                "결과 불명의 이슈 번호 / URL 불일치");
                }
                // Agent DRY_RUN이면 승인 후 발행을 생략한다.
                case "SKIPPED" -> require(
                        item.githubIssueNumber() == null && item.githubIssueUrl() == null,
                        "발행 생략 상태 오류");
                default -> throw invalid("발행 상태 오류");
            }
        }
        var expected = AgentRunMapper.summarize(result.items());
        require(expected.equals(result.summary()), "항목 집계 불일치");
        if (result.error() != null) {
            require(
                    result.status() == TaskStatus.FAILED && text(result.error().message()),
                    "전체 오류의 상태 / 메시지 오류");
            return;
        }
        require(result.status() == AgentRunMapper.expectedStatus(expected), "집계에 따른 작업 상태 불일치");
    }

    private boolean validUrl(AgentContracts.StartRequest r, AgentContracts.Item item) {
        try {
            URI uri = URI.create(item.githubIssueUrl());
            return "https".equals(uri.getScheme())
                    && "github.com".equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getPort() == -1
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getPath()
                            .equalsIgnoreCase(
                                    "/"
                                            + r.project().githubOwner()
                                            + "/"
                                            + r.project().githubRepo()
                                            + "/issues/"
                                            + item.githubIssueNumber());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank();
    }

    private static void require(boolean ok, String condition) {
        if (!ok) throw invalid(condition);
    }

    private static AgentResultValidationException invalid(String condition) {
        return new AgentResultValidationException(condition);
    }
}
