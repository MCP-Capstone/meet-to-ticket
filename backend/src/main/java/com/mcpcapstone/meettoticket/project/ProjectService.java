package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.common.error.*;

import io.swagger.v3.oas.annotations.media.Schema;

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

    public record ProjectData(
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
            Long projectId,
            @Schema(description = "프로젝트 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 내보내기")
            String name) {}

    public record MemberData(
            @Schema(description = "프로젝트 팀원 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "11")
            Long projectMemberId,
            @Schema(description = "서비스 사용자 ID. 미가입 팀원은 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            Long userId,
            @Schema(description = "회의록 매칭에 사용하는 이름", requiredMode = Schema.RequiredMode.REQUIRED, example = "박종민")
            String name,
            @Schema(description = "실제 GitHub 로그인명. 미연결은 null", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
            String githubName) {
        public static MemberData from(ProjectMember m) {
            return new MemberData(
                    m.getId(),
                    m.getUser() == null ? null : m.getUser().getId(),
                    m.getName(),
                    m.getGithubName());
        }
    }
}
