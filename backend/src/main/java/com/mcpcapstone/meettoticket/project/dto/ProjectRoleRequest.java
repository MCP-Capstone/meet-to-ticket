package com.mcpcapstone.meettoticket.project.dto;

import com.mcpcapstone.meettoticket.project.ProjectRole;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record ProjectRoleRequest(
		@Schema(description = "바꿀 역할", example = "ADMIN")
		@NotNull(message = "역할을 선택해 주세요.")
		ProjectRole role) {
}
