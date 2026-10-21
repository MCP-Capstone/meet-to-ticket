package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;

import com.mcpcapstone.meettoticket.analysis.TaskStatus;
import com.mcpcapstone.meettoticket.common.JsonCodec;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// LIVE: Agent(#52) GET /runs/{taskId} 응답을 변환해 검증한다.
class AgentResultValidatorTest {
	private static final String REPO_URL = "https://github.com/MCP-Capstone/mcp-capstone/issues/";

	private final JsonCodec json = new JsonCodec();
	private final AgentResultValidator validator = new AgentResultValidator();

	private AgentContracts.StartRequest request() {
		return new AgentContracts.StartRequest(
				UUID.randomUUID(),
				new AgentContracts.MeetingData(
						1L, "회의", LocalDate.of(2026, 10, 22), "Asia/Seoul", List.of(11L), "a.txt", "원문"),
				new AgentContracts.ProjectData(1L, "CSV", "MCP-Capstone", "mcp-capstone"),
				List.of(new AgentContracts.MemberData(11L, null, "박종민", "jongmiiin")));
	}

	private Map<String, Object> item(String review, String publish, Integer number, String url, String error) {
		Map<String, Object> item = new HashMap<>();
		item.put("taskItemId", UUID.randomUUID().toString());
		item.put("workKey", "CSV-08");
		item.put("title", "빈 결과 CSV 생성 오류 수정");
		item.put("contentJson", Map.of("description", "설명", "assignees", Map.of("names", List.of("박종민"))));
		item.put("action", "CREATE");
		item.put("reason", null);
		item.put("reviewStatus", review);
		item.put("publishStatus", publish);
		item.put("githubIssueNumber", number);
		item.put("githubIssueUrl", url);
		item.put("errorMessage", error);
		return item;
	}

	private JsonNode run(String status, List<Map<String, Object>> items) {
		Map<String, Object> run = new HashMap<>();
		run.put("taskId", "ignored");
		run.put("status", status);
		run.put("stage", "REPORTING");
		run.put("items", items);
		return json.tree(run);
	}

	private AgentContracts.Result map(AgentContracts.StartRequest r, JsonNode run) {
		return AgentRunMapper.toResult(r.taskId(), run);
	}

	@Test
	void 발행_성공과_반려가_섞인_결과는_COMPLETED로_저장한다() {
		var r = request();
		var result = map(r, run("SUCCEEDED", List.of(
				item("APPROVED", "SUCCEEDED", 7, REPO_URL + 7, null),
				item("REJECTED", "NOT_STARTED", null, null, null))));

		assertThatCode(() -> validator.validate(r, result, false)).doesNotThrowAnyException();
		assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
		assertThat(result.summary()).isEqualTo(new AgentContracts.Summary(2, 1, 1, 1, 0, 0, 0));
		assertThat(result.items().get(1).publishStatus()).isEqualTo("BLOCKED");
	}

	@Test
	void 프로젝트_저장소가_아닌_이슈_URL은_차단한다() {
		var r = request();
		var result = map(r, run("SUCCEEDED", List.of(
				item("APPROVED", "SUCCEEDED", 7, "https://github.com/other/repo/issues/7", null))));

		assertThatThrownBy(() -> validator.validate(r, result, false))
				.isExactlyInstanceOf(AgentResultValidationException.class)
				.hasMessageContaining("생성 성공의 이슈 URL 불일치");
	}

	@Test
	void 확인_필요_항목은_발행_제외로_보고_사유를_남긴다() {
		var r = request();
		var result = map(r, run("SUCCEEDED", List.of(item("NEEDS_CHECK", "NOT_STARTED", null, null, null))));

		assertThatCode(() -> validator.validate(r, result, false)).doesNotThrowAnyException();
		var mapped = result.items().getFirst();
		assertThat(mapped.reviewStatus()).isEqualTo("REJECTED");
		assertThat(mapped.publishStatus()).isEqualTo("BLOCKED");
		assertThat(mapped.reason()).startsWith("[확인 필요]");
	}

	@Test
	void 일부_발행_실패는_PARTIALLY_FAILED다() {
		var r = request();
		var result = map(r, run("SUCCEEDED", List.of(
				item("APPROVED", "SUCCEEDED", 7, REPO_URL + 7, null),
				item("APPROVED", "FAILED", null, null, "GitHub 422"))));

		assertThatCode(() -> validator.validate(r, result, false)).doesNotThrowAnyException();
		assertThat(result.status()).isEqualTo(TaskStatus.PARTIALLY_FAILED);
	}

	@Test
	void Agent_전체_실패는_오류_메시지와_함께_FAILED다() {
		var r = request();
		Map<String, Object> failed = new HashMap<>();
		failed.put("taskId", r.taskId().toString());
		failed.put("status", "FAILED");
		failed.put("errorCode", "MCP_UNAVAILABLE");
		failed.put("errorMessage", "MCP 서버에 연결하지 못했습니다.");
		var result = map(r, json.tree(failed));

		assertThatCode(() -> validator.validate(r, result, false)).doesNotThrowAnyException();
		assertThat(result.status()).isEqualTo(TaskStatus.FAILED);
		assertThat(result.error().message()).contains("MCP_UNAVAILABLE");
	}

	@Test
	void 진행_중_응답은_RUNNING으로_본다() {
		var r = request();
		var result = map(r, json.tree(Map.of("taskId", r.taskId().toString(), "status", "RUNNING")));

		assertThatCode(() -> validator.validate(r, result, false)).doesNotThrowAnyException();
		assertThat(result.status()).isEqualTo(TaskStatus.RUNNING);
	}
}
