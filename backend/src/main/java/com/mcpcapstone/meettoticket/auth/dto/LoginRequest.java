package com.mcpcapstone.meettoticket.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
		@NotBlank(message = "아이디를 입력해 주세요.")
		String loginId,

		@NotBlank(message = "비밀번호를 입력해 주세요.")
		String password) {
}
