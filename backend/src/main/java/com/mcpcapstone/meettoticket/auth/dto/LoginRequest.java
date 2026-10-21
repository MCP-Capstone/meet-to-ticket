package com.mcpcapstone.meettoticket.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "로그인 요청")
public record LoginRequest(
		@Schema(description = "아이디", example = "pm01")
		@NotBlank(message = "아이디를 입력해 주세요.")
		String loginId,

		@Schema(description = "비밀번호", example = "password1")
		@NotBlank(message = "비밀번호를 입력해 주세요.")
		String password) {
}
