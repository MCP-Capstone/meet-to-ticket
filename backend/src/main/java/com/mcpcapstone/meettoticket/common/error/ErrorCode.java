package com.mcpcapstone.meettoticket.common.error;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// API 명세의 에러 코드. 프론트는 message를 그대로 화면에 보여준다.
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
	DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
	INVALID_GITHUB_ACCOUNT(HttpStatus.BAD_REQUEST, "GitHub 계정을 찾을 수 없습니다. 로그인명을 확인해 주세요."),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
	PROJECT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근할 수 없는 프로젝트입니다."),
	PROJECT_ADMIN_REQUIRED(HttpStatus.FORBIDDEN, "프로젝트 관리자만 편집할 수 있습니다."),
	DUPLICATE_PROJECT_REPOSITORY(HttpStatus.CONFLICT, "이미 다른 프로젝트에 등록된 GitHub 저장소입니다."),
	PROJECT_NAME_MISMATCH(HttpStatus.BAD_REQUEST, "프로젝트 이름이 일치하지 않습니다."),
	PROJECT_ANALYSIS_IN_PROGRESS(HttpStatus.CONFLICT, "처리 중인 회의록이 있어 삭제할 수 없습니다. 처리가 끝난 뒤 다시 시도해 주세요."),
	MEMBER_USER_NOT_FOUND(HttpStatus.BAD_REQUEST, "가입하지 않은 사용자가 멤버에 포함되어 있습니다."),
	PROJECT_MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "프로젝트 멤버가 아닌 사용자입니다."),
	LAST_PROJECT_ADMIN(HttpStatus.CONFLICT, "관리자는 1명 이상 있어야 합니다. 다른 멤버를 먼저 관리자로 지정해 주세요."),
	MEETING_NOT_FOUND(HttpStatus.NOT_FOUND, "회의를 찾을 수 없습니다."),
	INVALID_MEETING_FILE(HttpStatus.BAD_REQUEST, "UTF-8 .txt 파일의 제목·날짜·참여자를 확인해 주세요."),
	PARTICIPANT_MAPPING_FAILED(HttpStatus.BAD_REQUEST, "미등록 또는 동명이인 참여자가 있어 연결할 수 없습니다."),
	INVALID_MEETING_INPUT(HttpStatus.BAD_REQUEST, "회의 날짜와 회의록(UTF-8 .md·.txt 파일 또는 붙여넣은 텍스트 중 하나)을 확인해 주세요."),
	TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."),
	TASK_NOT_FINISHED(HttpStatus.CONFLICT, "처리 중입니다. 진행 상태를 계속 조회해 주세요."),
	ANALYSIS_ALREADY_REQUESTED(HttpStatus.CONFLICT, "이미 분석을 요청한 회의입니다."),
	AGENT_ACCEPTANCE_FAILED(HttpStatus.BAD_GATEWAY, "Agent 실행 접수를 확인하지 못했습니다."),
	TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "티켓을 찾을 수 없습니다."),
	TICKET_ASSIGNEE_REQUIRED(HttpStatus.FORBIDDEN, "티켓 담당자만 수정하거나 상태를 바꿀 수 있습니다."),
	TICKET_STATUS_CONFLICT(HttpStatus.CONFLICT, "현재 티켓 상태에서는 할 수 없는 작업입니다."),
	TICKET_UPLOADER_REQUIRED(HttpStatus.FORBIDDEN, "회의록을 올린 사람만 대표 담당을 지정할 수 있습니다."),
	MEETING_REVIEW_INCOMPLETE(HttpStatus.CONFLICT, "해당 회의의 내 티켓을 모두 확인 또는 거절한 뒤 발행해 주세요."),
	PUBLISH_TARGET_UNSUPPORTED(HttpStatus.CONFLICT, "현재 일괄 발행은 GitHub 티켓만 지원합니다."),
	PUBLISH_SELECTION_CHANGED(HttpStatus.CONFLICT, "발행 대상이 변경됐습니다. 목록을 새로고침하고 다시 확인해 주세요."),
	NO_CONFIRMED_TICKETS(HttpStatus.CONFLICT, "발행할 확인된 티켓이 없습니다."),
	PUBLISH_BATCH_NOT_FOUND(HttpStatus.NOT_FOUND, "발행 요청을 찾을 수 없습니다."),
	PROCESSING_TYPE_TARGET_MISMATCH(HttpStatus.BAD_REQUEST, "GitHub 이외 대상은 신규 발행만 할 수 있습니다."),
	EXISTING_ISSUE_REQUIRED(HttpStatus.BAD_REQUEST, "연결이나 갱신할 열린 기존 이슈가 없습니다. 신규 발행으로 바꿔 주세요.");

	private final HttpStatus status;
	private final String message;
}
