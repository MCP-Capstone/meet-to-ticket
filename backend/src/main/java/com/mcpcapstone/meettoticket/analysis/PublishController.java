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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "발행", description = "확인한 티켓 일괄 발행과 발행 결과 조회")
@RestController
@RequiredArgsConstructor
public class PublishController {
    private final PublishService service;

    @Operation(operationId = "publishConfirmedTickets", summary = "확인한 N건 발행", description = "로그인 사용자가 담당인 확인 상태 티켓만 한 묶음으로 발행한다. projectId를 주면 그 프로젝트의 티켓만 담는다. 검토 대기와 거절 티켓은 발행하지 않는다. 담긴 티켓은 발행 중이 되어 같은 티켓을 다시 담을 수 없다(중복 클릭 방지). 202는 발행 성공이 아니라 접수를 뜻하며, 결과는 batchId로 조회한다.")
    @ApiResponse(responseCode = "202", description = "발행 접수", content = @Content(schema = @Schema(implementation = PublishService.Started.class),
                    examples = @ExampleObject(value = ApiExamples.PUBLISH_STARTED)))
    @ApiResponse(responseCode = "400", description = "projectId 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "409", description = "발행할 확인된 티켓이 없음 (NO_CONFIRMED_TICKETS)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_NO_CONFIRMED_TICKETS)))
    @ApiResponse(responseCode = "502", description = "Agent가 접수하지 않음. 티켓은 확인 상태로 남는다 (AGENT_ACCEPTANCE_FAILED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_AGENT_ACCEPTANCE_FAILED)))
    @PostMapping("/api/tickets/publish")
    public ResponseEntity<PublishService.Started> publish(
            @Parameter(description = "프로젝트 필터. 생략하면 모든 프로젝트의 확인된 티켓", example = "1") @RequestParam(required = false) Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(userId, projectId));
    }

    @Operation(operationId = "getPublishResult", summary = "발행 결과 조회", description = "발행을 요청한 사용자만 조회한다. status가 RUNNING이면 Agent 결과를 기다리는 중이므로 계속 조회한다. 건수 요약, 티켓별 이슈 링크와 실패 사유를 반환한다. 이 GET으로 발행을 다시 실행하지 않는다.")
    @ApiResponse(responseCode = "200", description = "발행 결과", content = @Content(schema = @Schema(implementation = PublishService.Result.class),
                    examples = @ExampleObject(value = ApiExamples.PUBLISH_RESULT)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "404", description = "발행 요청이 없거나 다른 사용자의 요청 (PUBLISH_BATCH_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PUBLISH_BATCH_NOT_FOUND)))
    @GetMapping("/api/publish-batches/{batchId}")
    public PublishService.Result result(
            @Parameter(description = "발행 묶음 ID", required = true) @PathVariable UUID batchId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.result(batchId, userId);
    }
}
