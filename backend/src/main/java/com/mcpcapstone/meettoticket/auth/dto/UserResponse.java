package com.mcpcapstone.meettoticket.auth.dto;

import com.mcpcapstone.meettoticket.user.User;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "로그인한 사용자")
public record UserResponse(
		@Schema(description = "사용자 ID", example = "1")
		Long userId,

		@Schema(description = "아이디", example = "pm01")
		String loginId,

		@Schema(description = "이름", example = "김PM")
		String name,

		@Schema(description = "GitHub 계정. 입력하지 않았으면 null", example = "octocat", nullable = true)
		String githubName,

		@Schema(description = "Slack 이메일. 입력하지 않았으면 null", example = "member@example.com", nullable = true)
		String slackEmail,

		@Schema(description = "Slack 사용자 ID. Agent가 이메일 조회에 성공하면 채운다.", example = "U0123456789", nullable = true)
		String slackUserId) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getLoginId(), user.getName(), user.getGithubName(), user.getSlackEmail(), user.getSlackUserId());
	}
}
