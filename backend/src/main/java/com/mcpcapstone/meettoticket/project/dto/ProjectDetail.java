package com.mcpcapstone.meettoticket.project.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

// 프로젝트 등록/편집 화면(TASK-007)의 응답 형식.
public record ProjectDetail(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long projectId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 내보내기") String name,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String description,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "MCP-Capstone") String githubOwner,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "mcp-capstone") String githubRepo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String notionPageUrl,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String slackChannelId,
		@Schema(description = "가입 사용자 멤버", requiredMode = Schema.RequiredMode.REQUIRED) List<Member> members,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Connections connections,
		@Schema(description = "로그인 사용자가 관리자이면 true", requiredMode = Schema.RequiredMode.REQUIRED) boolean canEdit) {

	public enum RepositoryPermission { GRANTED, DENIED, NO_ACCOUNT }

	public enum ConnectionStatus { CONNECTED, NOT_CONNECTED, ERROR }

	public record Member(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1") Long userId,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "함주헌") String name,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "juhun-ham") String githubName,
			@Schema(description = "프로젝트 관리자 여부", requiredMode = Schema.RequiredMode.REQUIRED) boolean admin,
			@Schema(description = "저장소 권한. GitHub 계정이 없으면 NO_ACCOUNT, 프로젝트 저장 때 확인한 결과로 GRANTED/DENIED, 확인 전이거나 확인에 실패하면 null",
					requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
			RepositoryPermission repositoryPermission) {
	}

	public record Connections(
			@Schema(description = "저장 때 권한 확인에 성공하면 CONNECTED, 멤버 일부 또는 Agent/MCP 전체 실패면 ERROR, 확인 전이거나 확인할 멤버가 없으면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConnectionStatus github,
			@Schema(description = "입력하지 않았으면 NOT_CONNECTED, 확인 전이면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConnectionStatus notion,
			@Schema(description = "입력하지 않았으면 NOT_CONNECTED, 확인 전이면 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) ConnectionStatus slack) {
	}
}
