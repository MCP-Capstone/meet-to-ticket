package com.mcpcapstone.meettoticket.analysis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;

// Agent만 결과를 보고하는 비공개 경로. 공개 API와 달리 세션 인증을 쓰지 않는다.
@Hidden
@RestController
@RequestMapping("/internal/publish-batches")
public class InternalPublicationBatchController {
    private final PublicationBatchService service;
    private final String internalToken;

    public InternalPublicationBatchController(
            PublicationBatchService service,
            @Value("${BACKEND_INTERNAL_TOKEN:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @PostMapping("/{batchId}/result")
    public ResponseEntity<Void> result(
            @PathVariable UUID batchId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Internal-Token", required = false) String agentToken,
            @Valid @RequestBody PublicationBatchService.ResultRequest request) {
        verifyToken(authorization, agentToken);
        service.applyResult(batchId, request);
        return ResponseEntity.accepted().build();
    }

    private void verifyToken(String authorization, String agentToken) {
        if (internalToken.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "내부 보고 토큰이 설정되지 않았습니다.");
        String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? authorization.substring(7) : null;
        if (!matches(agentToken) && !matches(bearer))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "내부 보고 토큰이 올바르지 않습니다.");
    }

    private boolean matches(String value) {
        return value != null && MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }
}
