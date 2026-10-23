package com.mcpcapstone.meettoticket.project.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ProjectDeleteRequest(
		@Schema(description = "확인용 프로젝트 이름. 저장된 이름과 같아야 삭제한다", example = "CSV 내보내기")
		@NotBlank(message = "삭제할 프로젝트 이름을 입력해 주세요.")
		String confirmName) {
}
