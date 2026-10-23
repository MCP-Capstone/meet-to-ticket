package com.mcpcapstone.meettoticket.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Agent POST /publish (TASK-034). 202로 접수만 하고 결과는 Agent가 /internal/publish-batches/{batchId}/result로 보고한다.
public interface PublishGateway {
    // 접수에 실패하면 AgentException. 실행 모드가 MOCK이면 접수와 함께 모의 결과를 바로 돌려준다.
    Optional<Result> submit(Request request);

    String mode();

    record Request(String batchId, List<Ticket> tickets) {}

    record Ticket(
            String ticketId,
            Project project,
            Long meetingId,
            String meetingTitle,
            LocalDate meetingDate,
            String title,
            JsonNode contentJson,
            String processingType,
            Integer targetIssueNumber,
            boolean processingTypeEdited,
            boolean previousOutcomeUnknown,
            // GITHUB, NOTION, SLACK. 빠지면 Agent가 GITHUB로 처리하므로 항상 보낸다.
            String publishTarget,
            // Slack 공지 멘션 대상. SLACK 대상일 때만 채운다.
            List<SlackMember> slackMembers,
            String reviewStatus) {}

    record Project(Long projectId, String name, String githubOwner, String githubRepo, String notionPageUrl, String slackChannelId) {}

    // Agent는 이 항목을 dict 그대로 읽어 키가 snake_case다.
    record SlackMember(String name, @JsonProperty("slack_user_id") String slackUserId) {}

    record Result(
            String batchId,
            String status,
            Map<String, Integer> summary,
            List<Item> items,
            String errorCode,
            String errorMessage) {}

    // status: PUBLISHED, LINKED, UPDATED, POSTED, FAILED, UNKNOWN. NOTION 대상은 notionPageId, notionPageUrl로 만든 페이지를,
    // SLACK 대상은 POSTED와 messageUrl로 게시한 메시지를 알려 준다.
    record Item(
            String ticketId,
            String processingType,
            String status,
            Integer githubIssueNumber,
            String githubIssueUrl,
            String commentUrl,
            List<String> droppedLabels,
            String errorCode,
            String errorMessage,
            Boolean retryable,
            String notionPageId,
            String notionPageUrl,
            String messageUrl) {}
}
