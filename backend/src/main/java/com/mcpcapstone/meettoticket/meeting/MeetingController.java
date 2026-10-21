package com.mcpcapstone.meettoticket.meeting;

import com.mcpcapstone.meettoticket.common.error.ErrorResponse;
import com.mcpcapstone.meettoticket.common.config.ApiExamples;

import com.mcpcapstone.meettoticket.auth.SessionConst;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@Tag(name = "회의", description = "UTF-8 TXT 업로드 및 저장된 회의 메타데이터 조회")
@RestController
@RequiredArgsConstructor
public class MeetingController {
    private final MeetingService service;

    @Operation(operationId = "uploadMeeting", summary = "TXT 회의록 업로드", description = "UTF-8 .txt를 multipart file로 전송한다. 제목·ISO 날짜·쉼표로 구분한 한 줄 참여자 이름을 파싱한다. 역할 설명이 붙은 여러 줄 참석자는 미지원. 참여자는 프로젝트 팀원 이름과 정확히 일치해야 한다. 앞뒤 공백과 최초 BOM을 제외한 본문·줄바꿈은 유지한다. 기본 업로드 상한은 10MB(MAX_UPLOAD_SIZE). 응답은 이름 대신 participantIds를 반환하므로 팀원 조회 결과와 연결한다. 업로드만으로 분석을 시작하지 않는다.")
    @ApiResponse(responseCode = "201", description = "회의 저장 완료", content = @Content(schema = @Schema(implementation = MeetingService.MeetingResponse.class),
                    examples = @ExampleObject(value = ApiExamples.MEETING)))
    @ApiResponse(responseCode = "400", description = "파일 형식·참여자 매칭·크기 오류. INVALID_MEETING_FILE / PARTICIPANT_MAPPING_FAILED / VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PARTICIPANT_MAPPING_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @PostMapping(
            value = "/api/projects/{projectId}/meetings",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MeetingService.MeetingResponse> upload(
            @Parameter(description = "프로젝트 ID", example = "1", required = true) @PathVariable Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId,
            @Parameter(description = "업로드할 UTF-8 .txt 회의록", required = true) @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(projectId, userId, file));
    }

    @Operation(operationId = "getMeeting", summary = "저장된 회의 조회", description = "회의 제목·날짜·참여자 ID·원본 파일명을 반환한다. 본문은 이 공개 응답에 포함하지 않는다.")
    @ApiResponse(responseCode = "200", description = "회의 메타데이터", content = @Content(schema = @Schema(implementation = MeetingService.MeetingResponse.class),
                    examples = @ExampleObject(value = ApiExamples.MEETING)))
    @ApiResponse(responseCode = "400", description = "경로 변수·요청 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @ApiResponse(responseCode = "404", description = "회의 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_MEETING_NOT_FOUND)))
    @GetMapping("/api/meetings/{meetingId}")
    public MeetingService.MeetingResponse get(
            @Parameter(description = "저장된 회의 ID", example = "2", required = true) @PathVariable Long meetingId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.get(meetingId, userId);
    }
}
