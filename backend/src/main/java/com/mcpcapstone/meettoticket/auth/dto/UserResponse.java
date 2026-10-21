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
		String name) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getLoginId(), user.getName());
	}
}
