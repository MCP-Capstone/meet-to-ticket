package com.mcpcapstone.meettoticket.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "상태 확인")
@SecurityRequirements
@RestController
@RequestMapping("/api")
public class HealthController {

	@Operation(summary = "서버 상태", description = "백엔드가 떠 있으면 {\"status\":\"UP\"}")
	@GetMapping("/health")
	public Map<String, String> health() {
		return Map.of("status", "UP");
	}
}
