package com.mcpcapstone.meettoticket.project.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 등록과 수정이 같은 형식을 쓴다. 문자열은 서비스에서 앞뒤 공백을 지우고, 빈 선택값은 null로 저장한다.
public record ProjectSaveRequest(
		@Schema(description = "프로젝트 이름", example = "CSV 내보내기")
		@NotBlank(message = "프로젝트 이름을 입력해 주세요.")
		@Size(max = 100, message = "프로젝트 이름은 100자 이하입니다.")
		String name,

		@Schema(description = "설명 (선택)", nullable = true, example = "관리자 사용자 목록 내보내기")
		@Size(max = 500, message = "설명은 500자 이하입니다.")
		String description,

		@Schema(description = "GitHub 저장소 소유자 (사용자 또는 조직)", example = "MCP-Capstone")
		@NotBlank(message = "GitHub 저장소 소유자를 입력해 주세요.")
		@Pattern(regexp = "^\\s*[a-zA-Z0-9](-?[a-zA-Z0-9]){0,38}\\s*$", message = "GitHub 저장소 소유자 형식을 확인해 주세요.")
		String githubOwner,

		@Schema(description = "GitHub 저장소 이름", example = "mcp-capstone")
		@NotBlank(message = "GitHub 저장소 이름을 입력해 주세요.")
		@Pattern(regexp = "^\\s*[a-zA-Z0-9._-]{1,100}\\s*$", message = "GitHub 저장소 이름 형식을 확인해 주세요.")
		String githubRepo,

		@Schema(description = "Notion 기획 페이지 URL (선택)", nullable = true, example = "https://www.notion.so/team/plan")
		@Size(max = 500, message = "Notion 페이지 URL은 500자 이하입니다.")
		@Pattern(regexp = "^\\s*(https://\\S+)?\\s*$", message = "Notion 페이지 URL은 https://로 시작해야 합니다.")
		String notionPageUrl,

		@Schema(description = "Slack 채널 ID (선택)", nullable = true, example = "C0123ABCD")
		@Pattern(regexp = "^\\s*([a-zA-Z0-9]{1,30})?\\s*$", message = "Slack 채널 ID 형식을 확인해 주세요.")
		String slackChannelId,

		@Schema(description = "변경 후 전체 멤버의 사용자 ID. 등록자와 관리자는 빠져 있어도 멤버로 남는다", example = "[1, 2]")
		@NotNull(message = "멤버 목록을 보내 주세요.")
		@Size(max = 100, message = "멤버는 100명 이하입니다.")
		List<@NotNull(message = "멤버 사용자 ID를 확인해 주세요.") Long> memberUserIds) {
}
