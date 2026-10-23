package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.project.dto.ProjectDetail;
import com.mcpcapstone.meettoticket.project.dto.ProjectSaveRequest;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final UserRepository users;

    public void requireAccess(Long projectId, Long userId) {
        if (!members.existsByProjectIdAndUserId(projectId, userId))
            throw new BusinessException(ErrorCode.PROJECT_ACCESS_DENIED);
    }

    // 프로젝트 설정 편집과 삭제(TASK-009) 전에 부른다. 멤버가 아니면 403 접근 불가, 멤버지만 관리자가 아니면 403 관리자 필요.
    public void requireAdmin(Long projectId, Long userId) {
        ProjectMember me =
                members.findByProjectIdAndUserId(projectId, userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_ACCESS_DENIED));
        if (!me.isAdmin()) throw new BusinessException(ErrorCode.PROJECT_ADMIN_REQUIRED);
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

    public ProjectDetail detail(Long projectId, Long userId) {
        requireAccess(projectId, userId);
        return detail(projects.getReferenceById(projectId), userId);
    }

    // 등록한 사용자는 관리자이자 멤버가 된다 (REQ-029).
    @Transactional
    public ProjectDetail create(ProjectSaveRequest request, Long userId) {
        String owner = request.githubOwner().trim();
        String repo = request.githubRepo().trim();
        if (projects.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(owner, repo).isPresent())
            throw new BusinessException(ErrorCode.DUPLICATE_PROJECT_REPOSITORY);
        Project project = projects.save(new Project(request.name().trim(), owner, repo));
        apply(project, request);
        User creator = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        members.save(new ProjectMember(project, creator, creator.getName(), creator.getGithubName(), ProjectRole.ADMIN));
        saveMembers(project, request.memberUserIds(), request.memberSlackEmails());
        return detail(project, userId);
    }

    @Transactional
    public ProjectDetail update(Long projectId, ProjectSaveRequest request, Long userId) {
        requireAdmin(projectId, userId);
        Project project = projects.getReferenceById(projectId);
        projects.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(request.githubOwner().trim(), request.githubRepo().trim())
                .filter(other -> !other.getId().equals(projectId))
                .ifPresent(other -> { throw new BusinessException(ErrorCode.DUPLICATE_PROJECT_REPOSITORY); });
        apply(project, request);
        saveMembers(project, request.memberUserIds(), request.memberSlackEmails());
        return detail(project, userId);
    }

    // 관리자 지정과 해제 (REQ-030). 관리자는 항상 1명 이상 남긴다.
    @Transactional
    public ProjectDetail changeRole(Long projectId, Long targetUserId, ProjectRole role, Long userId) {
        List<ProjectMember> admins = members.lockByProjectIdAndRole(projectId, ProjectRole.ADMIN);
        // 잠금을 잡은 뒤 확인해야 그 사이에 해제된 관리자를 걸러낸다.
        if (admins.stream().noneMatch(m -> m.getUser() != null && m.getUser().getId().equals(userId)))
            requireAdmin(projectId, userId);
        ProjectMember target =
                members.findByProjectIdAndUserId(projectId, targetUserId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_MEMBER_NOT_FOUND));
        if (role == ProjectRole.MEMBER && target.isAdmin() && admins.size() <= 1)
            throw new BusinessException(ErrorCode.LAST_PROJECT_ADMIN);
        target.changeRole(role);
        return detail(projects.getReferenceById(projectId), userId);
    }

    private void apply(Project project, ProjectSaveRequest request) {
        project.update(
                request.name().trim(),
                blankToNull(request.description()),
                request.githubOwner().trim(),
                request.githubRepo().trim(),
                blankToNull(request.notionPageUrl()),
                blankToNull(request.slackChannelId()));
    }

    // memberUserIds는 변경 후 전체 멤버 목록이다. 관리자는 목록에 없어도 남긴다 (관리자 해제는 TASK-056).
    // 가입하지 않은 멤버(user 없음, 데모 데이터)는 이 목록으로 관리하지 않으므로 그대로 둔다.
    // memberSlackEmails는 사용자 계정에 저장한다. 같은 사용자가 여러 프로젝트에 있어도 Slack ID를 하나만 유지한다.
    private void saveMembers(Project project, List<Long> memberUserIds, Map<Long, String> memberSlackEmails) {
        Set<Long> wanted = new LinkedHashSet<>(memberUserIds);
        Map<Long, String> emails = memberSlackEmails == null ? Map.of() : memberSlackEmails;
        Set<Long> userIds = new LinkedHashSet<>(wanted);
        userIds.addAll(emails.keySet());
        Map<Long, User> found =
                users.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        if (found.size() != userIds.size()) throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND);

        List<ProjectMember> current = members.findByProjectIdOrderById(project.getId());
        Set<Long> projectUserIds = current.stream()
                .map(ProjectMember::getUser)
                .filter(Objects::nonNull)
                .map(User::getId)
                .collect(Collectors.toSet());
        projectUserIds.addAll(wanted);
        if (!projectUserIds.containsAll(emails.keySet()))
            throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND);
        for (Map.Entry<Long, String> entry : emails.entrySet()) {
            found.get(entry.getKey()).updateSlackEmail(blankToNull(entry.getValue()));
        }
        for (ProjectMember m : current) {
            if (m.getUser() == null) continue;
            if (wanted.remove(m.getUser().getId()) || m.isAdmin()) m.syncWithUser();
            else members.delete(m);
        }
        for (Long id : wanted) {
            User u = found.get(id);
            members.save(new ProjectMember(project, u, u.getName(), u.getGithubName()));
        }
    }

    // Agent의 실제 연결 확인은 다음 단계에서 기록한다. 입력 직후 null은 아직 확인하지 않았다는 뜻이다.
    private ProjectDetail detail(Project project, Long userId) {
        List<ProjectMember> all = members.findByProjectIdOrderById(project.getId());
        List<ProjectDetail.Member> registered = all.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> new ProjectDetail.Member(
                        m.getUser().getId(),
                        m.getUser().getName(),
                        m.getUser().getGithubName(),
                        m.getUser().getSlackEmail(),
                        m.getUser().getSlackUserId(),
                        m.isAdmin(),
                        m.getUser().getGithubName() == null ? ProjectDetail.RepositoryPermission.NO_ACCOUNT : null))
                .toList();
        boolean canEdit = all.stream()
                .anyMatch(m -> m.getUser() != null && m.getUser().getId().equals(userId) && m.isAdmin());
        return new ProjectDetail(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getGithubOwner(),
                project.getGithubRepo(),
                project.getNotionPageUrl(),
                project.getSlackChannelId(),
                registered,
                new ProjectDetail.Connections(null, project.notionConnectionStatus(), project.slackConnectionStatus()),
                canEdit);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
