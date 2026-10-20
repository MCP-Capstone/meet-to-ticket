package com.mcpcapstone.meettoticket.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "회원가입 요청")
public record SignupRequest(
		@Schema(description = "아이디 (영문 / 숫자 4~20자)", example = "pm01")
		@NotBlank(message = "아이디를 입력해 주세요.")
		@Pattern(regexp = "^[a-zA-Z0-9]{4,20}$", message = "아이디는 영문 / 숫자 4~20자입니다.")
		String loginId,

		@Schema(description = "비밀번호 (8~50자)", example = "password1")
		@NotBlank(message = "비밀번호를 입력해 주세요.")
		@Size(min = 8, max = 50, message = "비밀번호는 8~50자입니다.")
		String password,

		@Schema(description = "이름 (20자 이하). 회의록 참여자 / 담당자 이름과 맞춘다.", example = "김PM")
		@NotBlank(message = "이름을 입력해 주세요.")
		@Size(max = 20, message = "이름은 20자 이하입니다.")
		String name) {
}
