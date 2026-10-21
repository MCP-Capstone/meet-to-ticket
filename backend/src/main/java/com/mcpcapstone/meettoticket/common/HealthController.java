package com.mcpcapstone.meettoticket.common;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "상태 확인")
@SecurityRequirements
@RestController
@RequestMapping("/api")
public class HealthController {

	@Operation(summary = "서버 상태", description = "백엔드가 떠 있으면 {\"status\":\"UP\"}")
	@ApiResponse(responseCode = "200", description = "서버 기동 상태. 외부 서비스 연결 검증은 아님",
            useReturnTypeSchema = true,
            content = @Content(examples = @ExampleObject(value = "{\"status\":\"UP\"}")))
	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}
