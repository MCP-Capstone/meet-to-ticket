package com.mcpcapstone.meettoticket.meeting;

import com.mcpcapstone.meettoticket.auth.SessionConst;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
public class MeetingController {
    private final MeetingService service;

    @PostMapping(
            value = "/api/projects/{projectId}/meetings",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MeetingService.MeetingResponse> upload(
            @PathVariable Long projectId,
            @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(projectId, userId, file));
    }

    @GetMapping("/api/meetings/{meetingId}")
    public MeetingService.MeetingResponse get(
            @PathVariable Long meetingId,
            @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return service.get(meetingId, userId);
    }
}
