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
                        && request.meeting().meetingId().equals(result.meetingId()));
        require(result.status() != null && result.items() != null && result.createdAt() != null);
        if (!result.status().terminal()) {
            require(
                    result.finishedAt() == null
                            && result.items().isEmpty()
                            && result.summary() == null
                            && result.error() == null);
            require(result.status() != TaskStatus.RUNNING || result.stage() != null);
            return;
        }
        require(result.finishedAt() != null && result.stage() != null);
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
                            && text(item.reason()));
            require(item.workKey() == null || item.workKey().length() <= 100);
            JsonNode c = item.contentJson();
            require(c != null && c.isObject());
            JsonNode review = c.path("review");
            int correction = review.path("correctionCount").asInt(-1),
                    revision = c.path("revision").asInt(-1);
            require(
                    correction >= 0
                            && correction <= 2
                            && revision == correction + 1
                            && review.path("reviewedRevision").asInt(-1) == revision
                            && text(review.path("reason").asText("")));
            if ("REJECTED".equals(item.reviewStatus())) {
                require(
                        "BLOCKED".equals(item.publishStatus())
                                && item.githubIssueNumber() == null
                                && item.githubIssueUrl() == null
                                && review.path("approvedPayload").isNull()
                                && !c.path("publication").path("verified").asBoolean(false));
                rejected++;
                continue;
            }
            require("APPROVED".equals(item.reviewStatus()));
            approved++;
            if (!mock) validateContent(request, item);
            else require(c.path("publication").path("mock").asBoolean(false));
            switch (item.publishStatus() == null ? "" : item.publishStatus()) {
                case "SUCCEEDED" -> {
                    require(
                            !mock
                                    && item.githubIssueNumber() != null
                                    && item.githubIssueNumber() > 0
                                    && validUrl(request, item)
                                    && c.path("publication").path("verified").asBoolean(false)
                                    && item.errorMessage() == null);
                    OffsetDateTime.parse(c.path("publication").path("publishedAt").asText());
                    success++;
                }
                case "FAILED" -> {
                    require(
                            text(item.errorMessage())
                                    && item.githubIssueNumber() == null
                                    && item.githubIssueUrl() == null
                                    && !c.path("publication").path("verified").asBoolean(false));
                    failed++;
                }
                case "UNKNOWN" -> {
                    require(
                            text(item.errorMessage())
                                    && !c.path("publication").path("verified").asBoolean(false));
                    if (item.githubIssueNumber() != null || item.githubIssueUrl() != null)
                        require(item.githubIssueNumber() != null && validUrl(request, item));
                    unknown++;
                }
                case "SKIPPED" -> {
                    require(
                            mock
                                    && item.githubIssueNumber() == null
                                    && item.githubIssueUrl() == null
                                    && !c.path("publication").path("verified").asBoolean(false));
                    skipped++;
                }
                default -> throw invalid();
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
                                    skipped)));
        if (result.error() != null) {
            require(result.status() == TaskStatus.FAILED && text(result.error().message()));
            return;
        }
        require(summary != null);
        TaskStatus expected =
                failed + unknown == 0
                        ? TaskStatus.COMPLETED
                        : success + skipped > 0 ? TaskStatus.PARTIALLY_FAILED : TaskStatus.FAILED;
        require(result.status() == expected);
    }

    private void validateContent(AgentContracts.StartRequest request, AgentContracts.Item item) {
        JsonNode c = item.contentJson(), payload = c.path("review").path("approvedPayload");
        require(
                c.path("todo").isArray()
                        && c.path("acceptanceCriteria").isArray()
                        && c.path("assignees").isArray()
                        && c.path("evidence").isArray()
                        && !c.path("evidence").isEmpty());
        Set<String> logins = new HashSet<>();
        for (JsonNode a : c.path("assignees")) {
            long id = a.path("projectMemberId").asLong(-1);
            var member =
                    request.projectMembers().stream()
                            .filter(m -> m.projectMemberId() == id)
                            .findFirst()
                            .orElseThrow(AgentResultValidator::invalid);
            require(
                    text(member.githubName())
                            && member.githubName().equalsIgnoreCase(a.path("githubName").asText())
                            && "MATCHED".equals(a.path("mappingStatus").asText()));
            logins.add(member.githubName().toLowerCase(Locale.ROOT));
        }
        String[] lines = request.meeting().originalContent().split("\\R", -1);
        for (JsonNode e : c.path("evidence")) {
            int start = e.path("startLine").asInt(0), end = e.path("endLine").asInt(0);
            String quote = e.path("quote").asText("");
            require(
                    start > 0
                            && end >= start
                            && end <= lines.length
                            && text(quote)
                            && String.join("\n", Arrays.copyOfRange(lines, start - 1, end))
                                    .contains(quote));
        }
        JsonNode due = c.path("due");
        require(due.isObject());
        if (due.hasNonNull("date")) LocalDate.parse(due.path("date").asText());
        if (due.hasNonNull("time")) {
            require(due.hasNonNull("date"));
            LocalTime.parse(due.path("time").asText());
        }
        require(
                payload.isObject()
                        && request.project().githubOwner().equals(payload.path("owner").asText())
                        && request.project().githubRepo().equals(payload.path("repo").asText())
                        && item.title().equals(payload.path("title").asText())
                        && text(payload.path("body").asText())
                        && payload.path("assignees").isArray());
        Set<String> actual = new HashSet<>();
        payload.path("assignees").forEach(a -> actual.add(a.asText().toLowerCase(Locale.ROOT)));
        require(actual.equals(logins));
        require(!payload.has("state") && !payload.has("issueNumber") && !payload.has("token"));
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

    private static void require(boolean ok) {
        if (!ok) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Agent 응답이 요청·검수·발행 계약과 일치하지 않습니다.");
    }
}
