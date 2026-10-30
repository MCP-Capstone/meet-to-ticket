package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.config.ApiExamples;
import com.mcpcapstone.meettoticket.common.error.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;
import org.springframework.web.multipart.MultipartFile;

/**
 * 회의록 처리 요청 (기획 문서 REQ-006). 회의 저장과 분석 접수를 한 번에 한다.
 * 기존 업로드(POST /api/projects/{projectId}/meetings)와 분석 시작(POST /api/meetings/{meetingId}/analysis-tasks)은
 * 프론트가 이 API로 옮길 때까지 그대로 둔다.
 */
@Tag(name = "분석", description = "작업 접수·폴링·최종 항목 조회. SSE 및 사용자 재실행 API 없음")
@RestController
@RequiredArgsConstructor
public class MeetingAnalysisController {
    private final MeetingAnalysisService service;

    @Operation(operationId = "requestMeetingAnalysis", summary = "회의록 처리 요청", description = "업로더가 고른 회의 날짜와 회의록(UTF-8 .md·.txt 파일 file 또는 붙여넣은 텍스트 content 중 하나)을 multipart로 받아 회의를 저장하고 분석 작업을 접수한다. 참석자 줄과 멤버 GitHub 계정은 요구하지 않는다(계정이 없으면 이슈에 이름만 적는다). 제목은 title, 회의록 첫 #·## 제목줄, 파일 이름, \"<회의 날짜> 회의\" 순으로 정한다. 접수 이후 동작은 분석 시작 API와 같다: 작업과 큐 발행 기록을 함께 저장한 뒤 taskId를 RabbitMQ에 발행하고, 202는 접수 성공이며 이슈 생성 성공을 뜻하지 않는다. 프론트는 반환된 taskId로 상태·결과 API를 조회한다.")
    @ApiResponse(responseCode = "202", description = "회의 저장과 작업 접수", content = @Content(schema = @Schema(implementation = AnalysisCoordinator.Started.class),
                    examples = @ExampleObject(value = ApiExamples.ANALYSIS_ACCEPTED)))
    @ApiResponse(responseCode = "400", description = "회의 날짜 누락·형식 오류, 회의록 없음·둘 다 입력, 확장자·인코딩 오류. INVALID_MEETING_INPUT / VALIDATION_FAILED", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_INVALID_MEETING_INPUT)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @PostMapping(
            value = "/api/projects/{projectId}/analysis-tasks",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AnalysisCoordinator.Started> request(
            @Parameter(description = "프로젝트 ID", example = "1", required = true) @PathVariable Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId,
            @Parameter(description = "회의 날짜 (YYYY-MM-DD). 상대 기한 계산 기준", example = "2026-10-22", required = true) @RequestParam(value = "meetingDate", required = false) String meetingDate,
            @Parameter(description = "회의 제목. 비우면 회의록 첫 제목줄 또는 파일 이름") @RequestParam(value = "title", required = false) String title,
            @Parameter(description = "붙여넣은 회의록 텍스트. file과 함께 보내지 않는다") @RequestParam(value = "content", required = false) String content,
            @Parameter(description = "회의록 파일 (UTF-8 .md 또는 .txt). content와 함께 보내지 않는다") @RequestPart(value = "file", required = false) MultipartFile file) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(service.request(projectId, userId, meetingDate, title, content, file));
    }
}
