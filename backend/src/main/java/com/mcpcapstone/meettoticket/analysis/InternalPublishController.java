package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.PublishGateway;

import io.swagger.v3.oas.annotations.Hidden;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

// Agent가 일괄 발행 결과를 보고하는 내부 경로. 세션이 아니라 내부 토큰으로 보호한다.
@Hidden
@RestController
@RequestMapping("/internal/publish-batches")
public class InternalPublishController {
    private final PublishService service;
    private final String internalToken;

    public InternalPublishController(PublishService service, @Value("${BACKEND_INTERNAL_TOKEN:}") String internalToken) {
        this.service = service;
        this.internalToken = internalToken;
    }

    @PostMapping("/{batchId}/result")
    public ResponseEntity<Void> result(
            @PathVariable UUID batchId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "X-Internal-Token", required = false) String agentToken,
            @RequestBody PublishGateway.Result result) {
        verifyToken(authorization, agentToken);
        service.applyResult(batchId, result);
        return ResponseEntity.accepted().build();
    }

    private void verifyToken(String authorization, String agentToken) {
        if (internalToken.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "내부 보고 토큰이 설정되지 않았습니다.");
        String bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7) ? authorization.substring(7) : null;
        if (!matches(agentToken) && !matches(bearer))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "내부 보고 토큰이 올바르지 않습니다.");
    }

    private boolean matches(String supplied) {
        return supplied != null && MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
}
