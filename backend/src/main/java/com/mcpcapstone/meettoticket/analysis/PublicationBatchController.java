package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.error.ErrorResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "내 티켓", description = "담당자별로 저장된 티켓 조회·발행")
@RestController
@RequiredArgsConstructor
public class PublicationBatchController {
    private final PublicationBatchService service;

    @Operation(operationId = "publishConfirmedTickets", summary = "확인한 내 티켓 일괄 발행",
            description = "로그인 사용자가 담당인 CONFIRMED 티켓을 한 발행 묶음으로 Agent에 보낸다. 같은 티켓은 결과 콜백 전 PUBLISHING으로 바꿔 중복 클릭을 막는다. 결과는 비동기로 저장된다.")
    @ApiResponse(responseCode = "202", description = "발행 묶음 접수", content = @Content(schema = @Schema(implementation = PublicationBatchService.Accepted.class)))
    @ApiResponse(responseCode = "409", description = "발행할 확인 티켓 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping("/api/tickets/publish")
    public ResponseEntity<PublicationBatchService.Accepted> publish(
            @Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.start(userId));
    }
}
