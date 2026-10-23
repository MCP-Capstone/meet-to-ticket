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

import java.lang.annotation.*;
import java.util.UUID;

@Tag(name = "내 티켓", description = "담당자별로 저장된 티켓 조회")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tickets/{ticketId}")
public class TicketController {
    private final TicketReviewService service;

    // 티켓 API의 공통 오류 응답
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "200", description = "카드에 보일 티켓 (고친 값 반영)", content = @Content(schema = @Schema(implementation = TicketReviewService.TicketDetail.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET_DETAIL)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "내가 담당인 티켓이 아님", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_ACCESS_DENIED)))
    @ApiResponse(responseCode = "404", description = "티켓 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @interface TicketResponses {}

    // 상태를 바꾸는 API의 409 응답
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(responseCode = "409", description = "지금 상태에서 할 수 없는 작업 (기획 4.2 표 밖의 전이)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_STATE_CONFLICT)))
    @interface ConflictResponse {}

    @Operation(operationId = "getTicket", summary = "티켓 상세 조회", description = "카드에 보일 제목, 본문, 담당자, 기한, 라벨, 근거, 미결 질문, 경고, 중복 판단 결과를 반환한다. 담당자가 고친 값이 있으면 그 값을 보여준다. 담당자만 볼 수 있다.")
    @TicketResponses
    @GetMapping
    public TicketReviewService.TicketDetail get(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.detail(ticketId, userId);
    }

    @Operation(operationId = "editTicket", summary = "카드 수정", description = "제목, 본문, 담당자, 기한 중 보낸 칸만 고친다. 검토 대기(PENDING_REVIEW)에서만 고칠 수 있다. 담당자를 다른 멤버로 바꾸면 그 멤버의 내 티켓으로 옮겨 간다. 일괄 발행은 고친 값을 쓴다.")
    @TicketResponses
    @ConflictResponse
    @ApiResponse(responseCode = "400", description = "제목 공백·길이 초과, 시각만 있는 기한, 같은 프로젝트 멤버가 아닌 담당자", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_INVALID_TICKET_ASSIGNEE)))
    @PatchMapping
    public TicketReviewService.TicketDetail edit(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Valid @RequestBody TicketReviewService.TicketEditRequest request,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.edit(ticketId, userId, request);
    }

    @Operation(operationId = "confirmTicket", summary = "확인", description = "검토 대기 → 확인. 확인한 티켓만 일괄 발행된다.")
    @TicketResponses
    @ConflictResponse
    @PostMapping("/confirm")
    public TicketReviewService.TicketDetail confirm(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.change(ticketId, userId, TicketReviewService.Transition.CONFIRM);
    }

    @Operation(operationId = "rejectTicket", summary = "거절", description = "검토 대기 → 거절. 거절한 티켓은 발행하지 않는다.")
    @TicketResponses
    @ConflictResponse
    @PostMapping("/reject")
    public TicketReviewService.TicketDetail reject(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.change(ticketId, userId, TicketReviewService.Transition.REJECT);
    }

    @Operation(operationId = "reopenTicket", summary = "다시 열기", description = "확인 → 검토 대기.")
    @TicketResponses
    @ConflictResponse
    @PostMapping("/reopen")
    public TicketReviewService.TicketDetail reopen(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.change(ticketId, userId, TicketReviewService.Transition.REOPEN);
    }

    @Operation(operationId = "restoreTicket", summary = "되돌리기", description = "거절 → 검토 대기.")
    @TicketResponses
    @ConflictResponse
    @PostMapping("/restore")
    public TicketReviewService.TicketDetail restore(
            @Parameter(description = "티켓 ID") @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.change(ticketId, userId, TicketReviewService.Transition.RESTORE);
    }
}
