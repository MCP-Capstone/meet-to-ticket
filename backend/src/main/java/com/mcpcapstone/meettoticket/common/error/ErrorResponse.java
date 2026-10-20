package com.mcpcapstone.meettoticket.common.error;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "에러 응답. 프론트는 message를 그대로 화면에 보여준다.")
public record ErrorResponse(
		@Schema(description = "에러 코드 (ErrorCode 이름)", example = "VALIDATION_FAILED")
		String code,

		@Schema(description = "사용자에게 보여줄 메시지", example = "입력값을 확인해 주세요.")
		String message) {

	public static ErrorResponse of(ErrorCode errorCode) {
		return new ErrorResponse(errorCode.name(), errorCode.getMessage());
	}
}
