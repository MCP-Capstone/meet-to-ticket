package com.mcpcapstone.meettoticket.common.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;

// API 명세의 에러 코드. 프론트는 message를 그대로 화면에 보여준다.
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값을 확인해 주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    PROJECT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근할 수 없는 프로젝트입니다."),
    MEETING_NOT_FOUND(HttpStatus.NOT_FOUND, "회의를 찾을 수 없습니다."),
    INVALID_MEETING_FILE(HttpStatus.BAD_REQUEST, "UTF-8 .txt 파일의 제목·날짜·참여자를 확인해 주세요."),
    PARTICIPANT_MAPPING_FAILED(HttpStatus.BAD_REQUEST, "미등록 또는 동명이인 참여자가 있어 연결할 수 없습니다."),
    MEMBER_GITHUB_UNLINKED(HttpStatus.BAD_REQUEST, "참여자의 실제 GitHub 계정을 등록해 주세요."),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "분석 작업을 찾을 수 없습니다."),
    TASK_NOT_FINISHED(HttpStatus.CONFLICT, "처리 중입니다. 진행 상태를 계속 조회해 주세요."),
    ANALYSIS_ALREADY_REQUESTED(HttpStatus.CONFLICT, "이미 분석을 요청한 회의입니다."),
    AGENT_ACCEPTANCE_FAILED(HttpStatus.BAD_GATEWAY, "Agent 실행 접수를 확인하지 못했습니다.");

    private final HttpStatus status;
    private final String message;
}
