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

import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "티켓 이력", description = "에이전트 원본, 수정본, 발행본 리비전과 상태 이력 조회")
@RestController
@RequiredArgsConstructor
public class TicketHistoryController {
    private final TicketHistoryService service;

    @Operation(operationId = "getTicketHistory", summary = "티켓 리비전과 상태 이력 조회", description = "티켓의 에이전트 원본(AGENT), 담당자가 확정한 수정본(USER_EDIT), 발행 성공 최종본(PUBLISHED) 스냅샷과 상태 전이 이력, 마지막 승인(CONFIRMED) 사용자/시각을 반환한다. 수정본은 확정 시점에 바뀐 값이 있을 때만 있고, 카드 수정 호출마다 쌓지 않는다. 발행 생략(SKIPPED)은 PUBLISHED 리비전을 만들지 않는다. 티켓 담당자나 회의록을 올린 사람만 볼 수 있다.")
    @ApiResponse(responseCode = "200", description = "리비전과 상태 이력", content = @Content(schema = @Schema(implementation = TicketHistoryService.TicketHistory.class),
                    examples = @ExampleObject(value = ApiExamples.TICKET_HISTORY)))
    @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_UNAUTHORIZED)))
    @ApiResponse(responseCode = "403", description = "티켓 담당자도 회의록을 올린 사람도 아님 (TICKET_HISTORY_FORBIDDEN)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_HISTORY_FORBIDDEN)))
    @ApiResponse(responseCode = "404", description = "티켓 없음 (TICKET_NOT_FOUND)", content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                    examples = @ExampleObject(value = ApiExamples.ERROR_TICKET_NOT_FOUND)))
    @GetMapping("/api/tickets/{ticketId}/history")
    public TicketHistoryService.TicketHistory history(
            @Parameter(description = "티켓 ID", required = true) @PathVariable UUID ticketId,
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.get(ticketId, userId);
    }
}
