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
                        && request.meeting().meetingId().equals(result.meetingId()),
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
            if (!mock) validateContent(request, item);
            else require(c.path("publication").path("mock").asBoolean(false), "모의 발행 표시 누락");
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

    private void validateContent(AgentContracts.StartRequest request, AgentContracts.Item item) {
        JsonNode c = item.contentJson(), payload = c.path("review").path("approvedPayload");
        require(
                c.path("todo").isArray()
                        && c.path("acceptanceCriteria").isArray()
                        && c.path("assignees").isArray()
                        && c.path("evidence").isArray()
                        && !c.path("evidence").isEmpty(),
                "업무 내용·담당자·근거 배열 누락");
        Set<String> logins = new HashSet<>();
        for (JsonNode a : c.path("assignees")) {
            long id = a.path("projectMemberId").asLong(-1);
            var member =
                    request.projectMembers().stream()
                            .filter(m -> m.projectMemberId() == id)
                            .findFirst()
                            .orElseThrow(() -> invalid("프로젝트 범위 밖의 담당자"));
            require(text(member.githubName()), "등록된 담당자 GitHub 계정 누락");
            require(member.githubName().equalsIgnoreCase(a.path("githubName").asText()),
                    "담당자 GitHub 계정 불일치");
            require("MATCHED".equals(a.path("mappingStatus").asText()), "담당자 매칭 상태 오류");
            logins.add(member.githubName().toLowerCase(Locale.ROOT));
        }
        String[] lines = request.meeting().originalContent().split("\\R", -1);
        for (JsonNode e : c.path("evidence")) {
            int start = e.path("startLine").asInt(0), end = e.path("endLine").asInt(0);
            String quote = e.path("quote").asText("");
            require(start > 0 && end >= start && end <= lines.length, "근거 행 범위 오류");
            require(text(quote), "근거 인용 누락");
            require(
                    String.join("\n", Arrays.copyOfRange(lines, start - 1, end)).contains(quote),
                    "원문에 없는 근거 인용");
        }
        JsonNode due = c.path("due");
        require(due.isObject(), "기한 객체 누락");
        if (due.hasNonNull("date")) LocalDate.parse(due.path("date").asText());
        if (due.hasNonNull("time")) {
            require(due.hasNonNull("date"), "기한 시각에 날짜 누락");
            LocalTime.parse(due.path("time").asText());
        }
        require(payload.isObject(), "승인 발행 인자 객체 누락");
        require(
                request.project().githubOwner().equals(payload.path("owner").asText()),
                "승인 저장소 소유자 불일치");
        require(
                request.project().githubRepo().equals(payload.path("repo").asText()),
                "승인 저장소 이름 불일치");
        require(item.title().equals(payload.path("title").asText()), "승인 제목 불일치");
        require(text(payload.path("body").asText()), "승인 본문 누락");
        require(payload.path("assignees").isArray(), "승인 담당자 배열 누락");
        Set<String> actual = new HashSet<>();
        payload.path("assignees").forEach(a -> actual.add(a.asText().toLowerCase(Locale.ROOT)));
        require(actual.equals(logins), "승인 인자와 담당자 계정 불일치");
        require(
                !payload.has("state") && !payload.has("issueNumber") && !payload.has("token"),
                "신규 생성에 허용하지 않는 필드 포함");
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
