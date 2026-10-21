package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.auth.SessionConst;
import com.mcpcapstone.meettoticket.common.ExecutionSettings;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ProjectController {
    private final ProjectService service;
    private final ExecutionSettings executionSettings;

    @GetMapping("/api/projects")
    public ProjectList list(@SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return new ProjectList(service.list(userId), executionSettings.mode());
    }

    @GetMapping("/api/projects/{projectId}/members")
    public MemberList members(
            @PathVariable Long projectId,
            @SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) {
        return new MemberList(projectId, service.members(projectId, userId));
    }

    public record ProjectList(List<ProjectService.ProjectData> projects, String executionMode) {}

    public record MemberList(Long projectId, List<ProjectService.MemberData> members) {}
}
