package com.mcpcapstone.meettoticket.user;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mcpcapstone.meettoticket.common.config.ApiExamples;
import com.mcpcapstone.meettoticket.common.error.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "사용자", description = "프로젝트 멤버로 고를 가입 사용자 검색")
@RestController
@RequiredArgsConstructor
public class UserController {

	private static final int SEARCH_LIMIT = 20;

	private final UserRepository users;

	@Operation(operationId = "searchUsers", summary = "가입 사용자 검색", description = "이름 또는 GitHub 계정으로 찾는다. 검색어가 비어 있으면 가입 사용자를 이름순으로 최대 20명 돌려준다. Slack 이메일·사용자 ID도 프로젝트 멤버 설정용으로 함께 준다.")
	@ApiResponse(responseCode = "200", description = "사용자 목록", content = @Content(schema = @Schema(implementation = UserList.class),
			examples = @ExampleObject(value = ApiExamples.USERS)))
	@ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
			examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
	@GetMapping("/api/users")
	public UserList search(
			@Parameter(description = "이름 또는 GitHub 계정 일부", example = "함")
			@RequestParam(defaultValue = "") String keyword) {
		List<UserSummary> found = users.search(keyword.trim(), PageRequest.of(0, SEARCH_LIMIT)).stream()
				.map(u -> new UserSummary(u.getId(), u.getName(), u.getGithubName(), u.getSlackEmail(), u.getSlackUserId()))
				.toList();
		return new UserList(found);
	}

	public record UserList(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UserSummary> users) {
	}

	public record UserSummary(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long userId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "함주헌") String name,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "juhun-ham") String githubName,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "member@example.com") String slackEmail,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "U0123456789") String slackUserId) {
	}
}
