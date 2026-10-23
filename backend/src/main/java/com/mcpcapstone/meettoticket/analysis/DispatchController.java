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

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "전송 결과", description = "회의록 처리 뒤 담당자별로 보낸 티켓과 대표 담당 지정")
@RestController
@RequiredArgsConstructor
public class DispatchController {
    private final DispatchService service;

    @Operation(operationId = "getDispatchResult", summary = "전송 결과 조회", description = "회의록 처리 한 건에서 만든 티켓의 발행 대상, 받은 사람(ticket.assignee, 로그인 사용자면 mine=true), 상태와 건수 요약을 반환한다. GitHub 티켓은 읽기 전용 이슈 미리보기를 함께 준다. 같은 프로젝트 멤버면 볼 수 있고, 회의록을 올린 사람이면 canAssign=true와 대표 담당 후보(members)를 준다.")
    @ApiResponse(responseCode = "200", description = "전송 결과", content = @Content(schema = @Schema(implementation = DispatchService.DispatchResult.class),
                    examples = @ExampleObject(value = ApiExamples.DISPATCH_RESULT)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "프로젝트 멤버가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_PROJECT_ACCESS_DENIED)))
    @ApiResponse(responseCode = "404", description = "분석 작업 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TASK_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "아직 처리 중", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TASK_NOT_FINISHED)))
    @GetMapping("/api/analysis-tasks/{taskId}/tickets")
    public DispatchService.DispatchResult result(
            @Parameter(description = "분석 UUID") @PathVariable UUID taskId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.result(taskId, userId);
    }

    @Operation(operationId = "assignTicket", summary = "대표 담당 지정", description = "담당 지정 필요(ASSIGNEE_REQUIRED) 티켓에 같은 프로젝트 팀원 1명을 대표 담당으로 지정한다. 지정하면 검토 대기가 되어 그 팀원의 내 티켓으로 간다. 회의록을 올린 사람만 할 수 있다 (REQ-011).")
    @ApiResponse(responseCode = "200", description = "지정된 티켓", content = @Content(schema = @Schema(implementation = DispatchService.DispatchTicket.class)))
    @ApiResponse(responseCode = "400", description = "팀원 미선택", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "회의록을 올린 사람이 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_UPLOADER_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음, 또는 같은 프로젝트 팀원이 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "담당 지정 필요 상태가 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PutMapping("/api/tickets/{ticketId}/assignee")
    public DispatchService.DispatchTicket assign(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Valid @RequestBody DispatchService.AssignRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.assign(ticketId, userId, request.memberId());
    }
}
