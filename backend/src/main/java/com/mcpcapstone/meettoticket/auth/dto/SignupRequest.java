package com.mcpcapstone.meettoticket.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "아이디를 입력해 주세요.")
		@Pattern(regexp = "^[a-zA-Z0-9]{4,20}$", message = "아이디는 영문 / 숫자 4~20자입니다.")
		String loginId,

		@NotBlank(message = "비밀번호를 입력해 주세요.")
		@Size(min = 8, max = 50, message = "비밀번호는 8~50자입니다.")
		String password,

		@NotBlank(message = "이름을 입력해 주세요.")
		@Size(max = 20, message = "이름은 20자 이하입니다.")
		String name) {
}
