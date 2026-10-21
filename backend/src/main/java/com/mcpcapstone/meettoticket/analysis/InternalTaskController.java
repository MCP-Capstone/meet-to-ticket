package com.mcpcapstone.meettoticket.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@Hidden
@RestController
@RequestMapping("/internal/tasks")
public class InternalTaskController {

	private final AnalysisStore store;
	private final String internalToken;

	public InternalTaskController(AnalysisStore store,
			@Value("${BACKEND_INTERNAL_TOKEN:}") String internalToken) {
		this.store = store;
		this.internalToken = internalToken;
	}

	@PostMapping("/{taskId}/progress")
	public ResponseEntity<Void> progress(
			@PathVariable UUID taskId,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestHeader(value = "X-Internal-Token", required = false) String agentToken,
			@Valid @RequestBody ProgressRequest request) {
		verifyToken(authorization, agentToken);
		store.updateProgress(taskId, request.status(), request.stage());
		return ResponseEntity.accepted().build();
	}

	// 결과 본문으로 상태나 항목을 저장하지 않는다. 기존 Agent 폴링만 결과를 저장한다.
	@PostMapping("/{taskId}/result")
	public ResponseEntity<Void> result(
			@PathVariable UUID taskId,
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestHeader(value = "X-Internal-Token", required = false) String agentToken) {
		verifyToken(authorization, agentToken);
		return ResponseEntity.accepted().build();
	}

	private void verifyToken(String authorization, String agentToken) {
		if (internalToken.isBlank()) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "내부 보고 토큰이 설정되지 않았습니다.");
		}
		String bearerToken = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
				? authorization.substring(7) : null;
		// 현재 Agent의 X-Internal-Token과 Bearer 요청 모두 동일한 공유 토큰으로 검증한다.
		if (!matches(agentToken) && !matches(bearerToken)) {
			throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "내부 보고 토큰이 올바르지 않습니다.");
		}
	}

	private boolean matches(String suppliedToken) {
		return suppliedToken != null && MessageDigest.isEqual(
				internalToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8));
	}

	public record ProgressRequest(@NotNull TaskStatus status, @NotNull TaskStage stage) {
	}
}
