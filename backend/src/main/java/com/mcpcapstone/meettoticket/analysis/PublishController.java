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
import java.util.List;

@Tag(name = "발행", description = "확인한 티켓 일괄 발행과 발행 결과 조회")
@RestController
@RequiredArgsConstructor
public class PublishController {
    private final PublishService service;

    @Operation(operationId = "publishConfirmedTickets", summary = "확인한 N건 발행", description = "로그인 사용자가 담당인 확인 상태 티켓만 한 묶음으로 발행한다. meetingId를 주면 해당 회의의 본인 티켓을 모두 검토했는지 확인하며 ticketIds가 현재 확인된 티켓 ID와 일치해야 한다. 검토 미완료, 대상 변경, 지원하지 않는 발행 도구는 409다. projectId를 주면 그 프로젝트의 티켓만 담는다. 검토 대기와 거절 티켓은 발행하지 않는다. 담긴 티켓은 발행 중이 되어 같은 티켓을 다시 담을 수 없다(중복 클릭 방지). 202는 발행 성공이 아니라 접수를 뜻하며, 결과는 batchId로 조회한다.")
    @ApiResponse(responseCode = "202", description = "발행 접수", content = @Content(schema = @Schema(implementation = PublishService.Started.class),
                    examples = @ExampleObject(value = ApiExamples.PUBLISH_STARTED)))
    @ApiResponse(responseCode = "400", description = "projectId 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "409", description = "발행 대상 없음, 회의 검토 미완료, 이미 발행 중, 확인 대상 변경 또는 지원하지 않는 발행 도구", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_NO_CONFIRMED_TICKETS)))
    @ApiResponse(responseCode = "429", description = "GitHub 신규 이슈가 결과 모름이고 마지막 시도 뒤 기다리는 시간(PUBLISH_GITHUB_RECHECK_DELAY, 기본 3분)이 지나지 않음 (PUBLISH_RETRY_TOO_SOON)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PUBLISH_RETRY_TOO_SOON)))
    @ApiResponse(responseCode = "502", description = "Agent가 접수하지 않음. 티켓은 확인 상태로 남는다 (AGENT_ACCEPTANCE_FAILED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_AGENT_ACCEPTANCE_FAILED)))
    @PostMapping("/api/tickets/publish")
    public ResponseEntity<PublishService.Started> publish(
            @Parameter(description = "프로젝트 필터. 생략하면 모든 프로젝트의 확인된 티켓", example = "1") @RequestParam(required = false) Long projectId,
            @Parameter(description = "회의별 발행 필터. 지정 시 본인의 모든 티켓 검토 완료를 검증한다") @RequestParam(required = false) Long meetingId,
            @Parameter(description = "확인 창에서 동의한 티켓 ID. meetingId 지정 시 필수") @RequestParam(required = false) List<UUID> ticketIds,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(meetingId == null
                ? service.start(userId, projectId) : service.startMeeting(userId, projectId, meetingId, ticketIds));
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

	@Operation(operationId = "retryFailedTicket", summary = "실패한 티켓 다시 시도", description = "담당자만 실패 티켓을 재시도한다. retryable=false이면 409 PUBLISH_RETRY_NOT_ALLOWED다. MANUAL_CHECK이면 확인 결과를 먼저 남겨야 한다. 접수는 202이며 결과는 batchId로 조회한다. 행 잠금으로 중복 클릭을 차단한다.")
    @ApiResponse(responseCode = "202", description = "재시도 접수", content = @Content(schema = @Schema(implementation = PublishService.Started.class),
                    examples = @ExampleObject(value = ApiExamples.RETRY_STARTED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓이 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "실패 상태가 아니거나, 이미 다시 시도 중이거나, 확인 결과를 먼저 남겨야 하는 실패 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @ApiResponse(responseCode = "429", description = "GitHub 신규 이슈가 결과 모름이고 마지막 시도 뒤 기다리는 시간(PUBLISH_GITHUB_RECHECK_DELAY, 기본 3분)이 지나지 않음 (PUBLISH_RETRY_TOO_SOON)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PUBLISH_RETRY_TOO_SOON)))
    @ApiResponse(responseCode = "502", description = "Agent가 접수하지 않음. 티켓은 실패 상태로 남는다 (AGENT_ACCEPTANCE_FAILED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_AGENT_ACCEPTANCE_FAILED)))
    @PostMapping("/api/tickets/{ticketId}/retry")
    public ResponseEntity<PublishService.Started> retry(
            @Parameter(description = "실패한 티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retry(ticketId, userId));
    }

	@Operation(operationId = "retryUpdateComment", summary = "갱신 알림 댓글만 다시 보내기",
			description = "담당자만 UPDATED + COMMENT_FAILED 티켓의 댓글을 재전송한다. 본문은 다시 수정하지 않는다. 댓글 표식을 먼저 확인한다. 접수 202, 비담당자 403, 상태 충돌 409, 없는 티켓 404다. 결과는 batchId로 조회한다.")
	@ApiResponse(responseCode = "202", description = "알림 댓글 재전송 접수", content = @Content(schema = @Schema(implementation = PublishService.Started.class)))
	@ApiResponse(responseCode = "401", description = "로그인 필요")
	@ApiResponse(responseCode = "403", description = "티켓 담당자가 아님")
	@ApiResponse(responseCode = "404", description = "티켓 없음")
	@ApiResponse(responseCode = "409", description = "UPDATED + COMMENT_FAILED가 아니거나 이미 처리 중")
	@ApiResponse(responseCode = "502", description = "Agent 접수 실패. UPDATED 상태 유지")
	@PostMapping("/api/tickets/{ticketId}/retry-comment")
	public ResponseEntity<PublishService.Started> retryComment(@PathVariable UUID ticketId,
			@Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.retryComment(ticketId, userId));
	}
}
