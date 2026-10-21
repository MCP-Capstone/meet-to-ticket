package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.auth.SessionConst;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService service;
    private final com.mcpcapstone.meettoticket.agent.AgentGateway gateway;

    @GetMapping("/api/projects")
    public Object list(@SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return Map.of("projects", service.list(userId), "executionMode", gateway.mode());
    }

    @GetMapping("/api/projects/{projectId}/members")
    public Object members(
            @PathVariable Long projectId,
            @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return Map.of("projectId", projectId, "members", service.members(projectId, userId));
    }
}
