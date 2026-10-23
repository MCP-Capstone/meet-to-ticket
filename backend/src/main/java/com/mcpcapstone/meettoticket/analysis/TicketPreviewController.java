package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.SessionAttribute;

@Tag(name = "내 티켓")
@RestController
@RequiredArgsConstructor
public class TicketPreviewController {
	private final TicketPreviewService service;
	@Operation(summary = "내 티켓 발행 본문 미리보기", description = "담당자만 조회한다. 저장된 내용으로 본문을 생성하며 승인/발행 상태를 변경하지 않는다. GitHub는 Agent의 실제 발행과 같은 함수를 사용한다. 다른 도구는 상세 백로그만 반환한다. 미리보기 생성 실패는 502 TICKET_PREVIEW_UNAVAILABLE이다.")
	@GetMapping("/api/tickets/{ticketId}/preview")
	public TicketPreviewService.Result preview(@PathVariable UUID ticketId,
			@Parameter(hidden = true) @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
		return service.preview(ticketId, userId);
	}
}
