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

@Tag(name = "내 티켓", description = "담당자별로 저장된 티켓 조회")
@RestController
@RequiredArgsConstructor
public class MyTicketController {
    private final MyTicketService service;

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
}
