package com.mcpcapstone.meettoticket.agent;

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
            String reviewStatus) {}

    record Project(Long projectId, String name, String githubOwner, String githubRepo) {}

    record Result(
            String batchId,
            String status,
            Map<String, Integer> summary,
            List<Item> items,
            String errorCode,
            String errorMessage) {}

    // status: PUBLISHED, LINKED, UPDATED, FAILED, UNKNOWN
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
            Boolean retryable) {}
}
