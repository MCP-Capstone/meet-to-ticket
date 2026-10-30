package com.mcpcapstone.meettoticket.agent;

import com.mcpcapstone.meettoticket.analysis.TaskStage;
import com.mcpcapstone.meettoticket.analysis.TaskStatus;

import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Agent(#52) GET /runs/{taskId} 응답을 Spring 결과 형식으로 바꾼다.
// Agent 상태: RUNNING / SUCCEEDED / FAILED, 항목 ID: taskItemId, 집계 키도 다르다.
final class AgentRunMapper {
	private static final int MAX_TITLE = 255;
	// 발행 전 초안과 담당자 확인 대기 상태
	static final String DRAFT = "DRAFT";
	static final String PENDING = "PENDING";

	private AgentRunMapper() {
	}

	static AgentContracts.Result toResult(UUID taskId, JsonNode run) {
		String status = run.path("status").asText("");
		OffsetDateTime now = OffsetDateTime.now();
		if (status.equals("RUNNING") || status.equals("ACCEPTED")) {
			// 진행 중이면 단계와 진행 화면용 세부 정보(progress)를 준다. 세부 정보가 없으면 null
			JsonNode progress = run.path("progress").isObject() ? run.path("progress") : null;
			return new AgentContracts.Result(
					taskId, null, TaskStatus.RUNNING, stageOf(run, TaskStage.EXTRACTING), now, null, List.of(), null,
					null, progress);
		}

		List<AgentContracts.Item> items = new ArrayList<>();
		for (JsonNode node : run.path("items")) {
			items.add(toItem(node));
		}
		AgentContracts.Summary summary = summarize(items);

		if (status.equals("FAILED")) {
			String message = text(run.path("errorMessage"), "Agent 처리 중 오류가 발생했습니다.");
			String code = text(run.path("errorCode"), "AGENT_FAILED");
			return new AgentContracts.Result(
					taskId, null, TaskStatus.FAILED, stageOf(run, TaskStage.VERIFYING), now, now, items, summary,
					new AgentContracts.AgentError(code, code + ": " + message), null);
		}
		if (!status.equals("SUCCEEDED")) {
			throw new AgentResultValidationException("알 수 없는 Agent 상태: " + status);
		}
		return new AgentContracts.Result(
				taskId, null, expectedStatus(summary), TaskStage.VERIFYING, now, now, items, summary, null, null);
	}

	// Agent 검수 상태: APPROVED / REJECTED / NEEDS_CHECK / PENDING
	// 발행 전 초안(publishStatus DRAFT)은 담당자 확인 대기(PENDING)로 저장한다. (기획 문서, 회의록 처리는 초안 저장에서 멈춤)
	// 그 밖에는 Spring이 승인 / 반려만 저장하므로 승인 외에는 반려(발행 제외)로 본다. (Agent PUBLISH_ON_RUN)
	private static AgentContracts.Item toItem(JsonNode node) {
		if (node.path("publishStatus").asText("").equals(DRAFT)) {
			return item(node, node.hasNonNull("reason") ? node.path("reason").asText() : null, PENDING, DRAFT,
					null, null, null);
		}
		String review = node.path("reviewStatus").asText("");
		boolean approved = review.equals("APPROVED");
		String reason = node.hasNonNull("reason") ? node.path("reason").asText() : null;
		if (!approved) {
			String prefix = review.equals("NEEDS_CHECK") ? "[확인 필요] " : "";
			reason = prefix + (reason == null || reason.isBlank() ? "검수를 통과하지 못해 발행하지 않았습니다." : reason);
		}

		String publish = approved ? node.path("publishStatus").asText("") : "BLOCKED";
		String error = node.hasNonNull("errorMessage") ? node.path("errorMessage").asText() : null;
		Integer number = node.hasNonNull("githubIssueNumber") ? node.path("githubIssueNumber").asInt() : null;
		String url = node.hasNonNull("githubIssueUrl") ? node.path("githubIssueUrl").asText() : null;
		switch (publish) {
			case "SUCCEEDED", "SKIPPED" -> error = publish.equals("SUCCEEDED") ? null : error;
			case "FAILED", "UNKNOWN" -> error = error == null || error.isBlank() ? "GitHub 이슈 발행 결과를 확인하지 못했습니다." : error;
			case "BLOCKED" -> {
				number = null;
				url = null;
			}
			default -> {
				// 승인됐는데 발행하지 않은 경우(NOT_STARTED 등)는 실패로 보여준다.
				publish = "FAILED";
				error = error == null || error.isBlank() ? "승인된 항목이 발행되지 않았습니다." : error;
				number = null;
				url = null;
			}
		}

		return item(node, reason, approved ? "APPROVED" : "REJECTED", publish, number, url, error);
	}

	private static AgentContracts.Item item(JsonNode node, String reason, String review, String publish,
			Integer number, String url, String error) {
		String title = node.path("title").asText("");
		if (title.length() > MAX_TITLE) {
			title = title.substring(0, MAX_TITLE);
		}
		return new AgentContracts.Item(
				UUID.fromString(node.path("taskItemId").asText()),
				node.hasNonNull("workKey") ? node.path("workKey").asText() : null,
				title,
				node.path("contentJson"),
				node.path("action").asText("CREATE"),
				reason,
				review,
				publish,
				number,
				url,
				error);
	}

	static AgentContracts.Summary summarize(List<AgentContracts.Item> items) {
		int approved = 0, rejected = 0, success = 0, failed = 0, unknown = 0, skipped = 0, drafts = 0;
		for (var item : items) {
			switch (item.reviewStatus()) {
				case "APPROVED" -> approved++;
				case PENDING -> {
				}
				default -> rejected++;
			}
			switch (item.publishStatus()) {
				case "SUCCEEDED" -> success++;
				case "FAILED" -> failed++;
				case "UNKNOWN" -> unknown++;
				case "SKIPPED" -> skipped++;
				case DRAFT -> drafts++;
				default -> {
				}
			}
		}
		return new AgentContracts.Summary(items.size(), approved, rejected, success, failed, unknown, skipped, drafts);
	}

	static TaskStatus expectedStatus(AgentContracts.Summary s) {
		if (s.failed() + s.unknown() == 0) {
			return TaskStatus.COMPLETED;
		}
		return s.succeeded() + s.skipped() > 0 ? TaskStatus.PARTIALLY_FAILED : TaskStatus.FAILED;
	}

	private static TaskStage stageOf(JsonNode run, TaskStage fallback) {
		try {
			return TaskStage.valueOf(run.path("stage").asText(""));
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static String text(JsonNode node, String fallback) {
		String value = node.asText("");
		return value.isBlank() ? fallback : value;
	}
}
