package com.mcpcapstone.meettoticket.analysis;

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

import java.util.UUID;

@Tag(name = "분석", description = "작업 접수·폴링·최종 항목 조회. SSE 및 사용자 재실행 API 없음")
@RestController
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisCoordinator coordinator;
    private final AnalysisStore store;

    @Operation(operationId = "startAnalysis", summary = "분석 작업 접수", description = "요청 본문 없이 저장된 회의로 분석을 시작한다. 작업을 DB에 저장한 뒤 Agent POST /runs를 호출한다. 202는 이슈 생성 성공을 의미하지 않는다. 동일 회의 재요청은 409이며 자동 재발행하지 않는다. 프론트는 반환된 taskId로 상태를 조회한다.")
    @ApiResponse(responseCode = "202", description = "작업 접수", content = @Content(schema = @Schema(implementation = AnalysisCoordinator.Started.class),
                    examples = @ExampleObject(value = ApiExamples.ANALYSIS_ACCEPTED)))
    @ApiResponse(responseCode = "400", description = "형식 오류 또는 LIVE 참여자 GitHub 계정 미등록", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_MEMBER_GITHUB_UNLINKED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @ApiResponse(responseCode = "404", description = "회의 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_MEETING_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "이미 분석 요청한 회의", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_ANALYSIS_ALREADY_REQUESTED)))
    @ApiResponse(responseCode = "502", description = "Agent 접수 거절·연결 실패", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_AGENT_ACCEPTANCE_FAILED)))
    @PostMapping("/api/meetings/{meetingId}/analysis-tasks")
    public ResponseEntity<AnalysisCoordinator.Started> start(
            @Parameter(description = "저장된 회의 ID", example = "2", required = true) @PathVariable Long meetingId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(coordinator.start(meetingId, userId));
    }

    @Operation(operationId = "getAnalysisStatus", summary = "분석 상태 조회", description = "프론트가 약 2초 간격으로 조회한다. COMPLETED / PARTIALLY_FAILED / FAILED는 종료 상태다. 종료 시 항목 조회 후 폴링을 멈춘다. Spring의 Agent 폴링과 독립적이며 이 GET으로 분석·발행을 다시 실행하지 않는다. LIVE 진행 단계는 Agent 응답에 없으면 EXTRACTING으로 표시될 수 있다.")
    @ApiResponse(responseCode = "200", description = "현재 상태. 업무 처리 실패도 HTTP 200", content = @Content(schema = @Schema(implementation = AnalysisStore.TaskView.class),
                    examples = @ExampleObject(value = ApiExamples.STATUS_RUNNING)))
    @ApiResponse(responseCode = "400", description = "경로 변수·요청 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @ApiResponse(responseCode = "404", description = "작업 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TASK_NOT_FOUND)))
    @GetMapping("/api/analysis-tasks/{taskId}")
    public AnalysisStore.TaskView status(
            @Parameter(description = "분석 접수 시 발급된 UUID", example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81", required = true) @PathVariable UUID taskId, @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return store.status(taskId, userId);
    }

    @Operation(operationId = "getAnalysisItems", summary = "분석 최종 결과 조회", description = "종료된 작업의 항목과 집계를 반환한다. 업무 없음·모두 반려는 COMPLETED. 결과 불명은 UNKNOWN으로 보존하며 자동 재발행하지 않는다. SKIPPED는 발행 생략(기본 demo 응답)이다. 현재 Agent dry-run의 NOT_STARTED는 Spring에서 FAILED로 변환된다. rejected는 failed와 별개다. total=approved+rejected, approved=succeeded+failed+unknown+skipped. 전체 실패 상세는 상태 조회의 errorMessage를 확인한다.")
    @ApiResponse(responseCode = "200", description = "항목별 결과와 집계. 예시는 MOCK이며 실제 이슈 생성 없음", content = @Content(schema = @Schema(implementation = AnalysisStore.ItemList.class),
                    examples = {
                        @ExampleObject(name = "mockApproved", summary = "MOCK 승인 후 발행 생략", value = ApiExamples.ITEMS_MOCK),
                        @ExampleObject(name = "empty", summary = "업무 없음", value = ApiExamples.ITEMS_EMPTY),
                        @ExampleObject(name = "rejected", summary = "모두 검수 반려", value = ApiExamples.ITEMS_REJECTED),
                        @ExampleObject(name = "failed", summary = "모의 발행 전부 실패", value = ApiExamples.ITEMS_FAILED),
                        @ExampleObject(name = "partial", summary = "모의 발행 생략·실패 혼합", value = ApiExamples.ITEMS_PARTIAL),
                        @ExampleObject(name = "liveFormat", summary = "LIVE 성공 응답 형식만 설명하는 가상 예시", value = ApiExamples.ITEMS_LIVE_FORMAT)
                    }))
    @ApiResponse(responseCode = "400", description = "경로 변수·요청 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 소속 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @ApiResponse(responseCode = "404", description = "작업 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TASK_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "아직 처리 중", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TASK_NOT_FINISHED)))
    @GetMapping("/api/analysis-tasks/{taskId}/items")
    public AnalysisStore.ItemList items(
            @Parameter(description = "분석 접수 시 발급된 UUID", example = "9b12ec5a-1423-4b71-8e20-f8f28d306c81", required = true) @PathVariable UUID taskId, @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return store.result(taskId, userId);
    }
}
