package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.auth.SessionConst;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class AnalysisController {
    private final AnalysisCoordinator coordinator;
    private final AnalysisStore store;

    @PostMapping("/api/meetings/{meetingId}/analysis-tasks")
    public ResponseEntity<AnalysisCoordinator.Started> start(
            @PathVariable Long meetingId,
            @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(coordinator.start(meetingId, userId));
    }

    @GetMapping("/api/analysis-tasks/{taskId}")
    public AnalysisStore.TaskView status(
            @PathVariable UUID taskId, @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return store.status(taskId, userId);
    }

    @GetMapping("/api/analysis-tasks/{taskId}/items")
    public AnalysisStore.ItemList items(
            @PathVariable UUID taskId, @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return store.result(taskId, userId);
    }
}
