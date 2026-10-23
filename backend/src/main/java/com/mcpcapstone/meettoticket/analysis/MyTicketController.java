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

@Tag(name = "내 티켓", description = "담당자별로 저장된 티켓 조회")
@RestController
@RequiredArgsConstructor
public class MyTicketController {
    private final MyTicketService service;
    private final TicketService tickets;

    @Operation(operationId = "listMyTickets", summary = "내 티켓 조회", description = "로그인 사용자가 담당인 티켓만 반환한다. projectId를 주면 그 프로젝트의 티켓만 반환하고, counts도 같은 범위로 센다. 담당 지정 필요 상태의 티켓은 담당자가 없어 어느 사용자에게도 나오지 않는다.")
    @ApiResponse(responseCode = "200", description = "내 티켓과 상태별 건수. 없으면 빈 배열", content = @Content(schema = @Schema(implementation = MyTicketService.MyTickets.class),
                    examples = @ExampleObject(value = ApiExamples.MY_TICKETS)))
    @ApiResponse(responseCode = "400", description = "projectId 형식 오류", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @GetMapping("/api/tickets/me")
    public MyTicketService.MyTickets mine(
            @Parameter(description = "프로젝트 필터. 생략하면 모든 프로젝트", example = "1") @RequestParam(required = false) Long projectId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.list(userId, projectId);
    }

    @Operation(operationId = "editTicket", summary = "티켓 카드 수정", description = "담당자가 본문, 담당 멤버, 기한을 고친다. 검토 대기 상태에서만 할 수 있고, 보낸 항목만 바꾼다. 담당 멤버를 바꾸면 그 멤버의 내 티켓으로 옮겨 가며 이후 이 사용자는 수정할 수 없다.")
    @ApiResponse(responseCode = "400", description = "입력값 오류 (VALIDATION_FAILED). 보낸 항목이 하나도 없는 경우 포함", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND) 또는 담당으로 바꿀 멤버가 이 프로젝트에 없음 (PROJECT_MEMBER_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "현재 상태에서 할 수 없는 작업 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PatchMapping("/api/tickets/{ticketId}")
    public MyTicketService.Ticket edit(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Valid @RequestBody TicketService.EditRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.edit(ticketId, userId, request);
    }

    @Operation(operationId = "changeTicketPublication", summary = "발행 대상과 처리 유형 변경",
            description = "담당자가 카드에서 발행 대상(GITHUB, SLACK, NOTION)과 처리 유형(CREATE, LINK, UPDATE)을 바꾼다. 검토 대기에서만 할 수 있고, 보낸 항목만 바꾼다. "
                    + "GitHub 이외 대상으로 바꾸면 처리 유형은 CREATE로 고정된다. LINK, UPDATE는 중복 판단이 찾은 열린 기존 이슈가 있을 때만 된다 (닫힌 이슈는 다시 열지 않음).")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "400", description = "입력값 오류 (VALIDATION_FAILED), GitHub 이외 대상에 연결/갱신 (PROCESSING_TYPE_TARGET_MISMATCH), 열린 기존 이슈 없음 (EXISTING_ISSUE_REQUIRED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class), examples = {
                    @ExampleObject(name = "PROCESSING_TYPE_TARGET_MISMATCH", value = ApiExamples.ERROR_PROCESSING_TYPE_TARGET_MISMATCH),
                    @ExampleObject(name = "EXISTING_ISSUE_REQUIRED", value = ApiExamples.ERROR_EXISTING_ISSUE_REQUIRED)}))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "검토 대기가 아님 (TICKET_STATUS_CONFLICT), 결과 모름이 남아 발행 대상을 바꿀 수 없음 (PUBLISH_TARGET_LOCKED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = {@ExampleObject(name = "TICKET_STATUS_CONFLICT", value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT),
                            @ExampleObject(name = "PUBLISH_TARGET_LOCKED", value = ApiExamples.ERROR_PUBLISH_TARGET_LOCKED)}))
    @PatchMapping("/api/tickets/{ticketId}/publication")
    public MyTicketService.Ticket changePublication(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @RequestBody TicketService.PublicationRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.changePublication(ticketId, userId, request);
    }

    @Operation(operationId = "confirmTicket", summary = "티켓 확인", description = "검토 대기에서 확인으로 바꾼다. 확인한 티켓만 일괄 발행 대상이 된다. 담당자만 할 수 있다.")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "현재 상태에서 할 수 없는 작업 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PostMapping("/api/tickets/{ticketId}/confirm")
    public MyTicketService.Ticket confirm(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.confirm(ticketId, userId);
    }

    @Operation(operationId = "rejectTicket", summary = "티켓 거절", description = "검토 대기에서 거절로 바꾼다. 담당자만 할 수 있다.")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "현재 상태에서 할 수 없는 작업 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PostMapping("/api/tickets/{ticketId}/reject")
    public MyTicketService.Ticket reject(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.reject(ticketId, userId);
    }

    @Operation(operationId = "reopenTicket", summary = "티켓 다시 열기", description = "확인에서 검토 대기로 되돌린다. 담당자만 할 수 있다.")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "현재 상태에서 할 수 없는 작업 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PostMapping("/api/tickets/{ticketId}/reopen")
    public MyTicketService.Ticket reopen(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.reopen(ticketId, userId);
    }

    @Operation(operationId = "restoreTicket", summary = "티켓 되돌리기", description = "거절에서 검토 대기로 되돌린다. 담당자만 할 수 있다.")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "현재 상태에서 할 수 없는 작업 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PostMapping("/api/tickets/{ticketId}/restore")
    public MyTicketService.Ticket restore(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.restore(ticketId, userId);
    }

    @Operation(operationId = "reviseFailedTicket", summary = "실패한 티켓 수정하러 되돌리기", description = "발행에 실패한 티켓을 검토 대기로 되돌려 고친 뒤 다시 확인할 수 있게 한다. 확인 결과가 필요한 실패(publishFailure.resolution=MANUAL_CHECK)는 먼저 resolve-outcome으로 결론을 내야 하므로 409다. 지난 발행 결과(오류 코드, 재시도 가능 여부, 결과 모름)는 티켓에서 비우며, 지난 묶음의 결과 조회는 그대로 유지된다. 지난 발행이 결과 모름이었다면 다시 발행할 때 Agent가 이미 반영된 이슈, 댓글, 섹션을 반드시 대조한다. 실패 상태가 아니면 409다. 담당자만 할 수 있다.")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "실패 상태가 아니거나 확인 결과가 먼저 필요함 (TICKET_STATUS_CONFLICT)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PostMapping("/api/tickets/{ticketId}/revise")
    public MyTicketService.Ticket revise(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.revise(ticketId, userId);
    }

    @Operation(operationId = "resolveTicketOutcome", summary = "결과 모름 실패를 확인 결과로 정리",
            description = "재대조할 수 없는 대상(Notion, Slack)의 결과 모름 실패(publishFailure.resolution=MANUAL_CHECK)를 담당자가 대상 도구에서 직접 확인한 결과로 정리한다. "
                    + "이 대상은 이미 반영됐는지 찾는 도구가 없어 Agent가 결과 모름 뒤 재발행을 *_VERIFY_REQUIRED로 막으므로 이 확인이 필요하다. "
                    + "applied=true면 발행됨으로 끝내고 url(선택, 그 대상의 https 링크)을 결과 링크로 저장한다. "
                    + "applied=false면 결과 모름 표시를 풀고 VERIFIED_NOT_APPLIED(재시도 가능) 실패로 바꿔, 다시 시도하면 차단 없이 발행한다. "
                    + "GitHub는 다시 시도하면 Agent가 재대조하므로 받지 않는다. MANUAL_CHECK 실패가 아니면 409다. 담당자만 할 수 있다.")
    @ApiResponse(responseCode = "200", description = "변경된 티켓", content = @Content(schema = @Schema(implementation = MyTicketService.Ticket.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET)))
    @ApiResponse(responseCode = "400", description = "입력값 오류 (VALIDATION_FAILED). applied 누락, applied=true인데 대상의 https 링크가 아닌 url", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_VALIDATION_FAILED)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자가 아님 (TICKET_ASSIGNEE_REQUIRED)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ASSIGNEE_REQUIRED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @ApiResponse(responseCode = "409", description = "확인 결과가 필요한 실패(MANUAL_CHECK)가 아님 (TICKET_STATUS_CONFLICT). GitHub 티켓 포함", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATUS_CONFLICT)))
    @PostMapping("/api/tickets/{ticketId}/resolve-outcome")
    public MyTicketService.Ticket resolveOutcome(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Valid @RequestBody TicketService.ResolveOutcomeRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return tickets.resolveOutcome(ticketId, userId, request);
    }
}
