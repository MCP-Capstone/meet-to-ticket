package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.common.error.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectMemberRepository members;

    public void requireAccess(Long projectId, Long userId) {
        if (!members.existsByProjectIdAndUserId(projectId, userId))
            throw new BusinessException(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    public List<ProjectData> list(Long userId) {
        return members.findProjectsForUser(userId).stream()
                .map(p -> new ProjectData(p.getId(), p.getName()))
                .toList();
    }

    public List<MemberData> members(Long projectId, Long userId) {
        requireAccess(projectId, userId);
        return members.findByProjectIdOrderById(projectId).stream().map(MemberData::from).toList();
    }

    public record ProjectData(Long projectId, String name) {}

    public record MemberData(Long projectMemberId, Long userId, String name, String githubName) {
        public static MemberData from(ProjectMember m) {
            return new MemberData(
                    m.getId(),
                    m.getUser() == null ? null : m.getUser().getId(),
                    m.getName(),
                    m.getGithubName());
        }
    }
}
