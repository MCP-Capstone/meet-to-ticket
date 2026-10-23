package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.agent.RepositoryPermissionGateway;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.project.dto.ProjectDetail;
import com.mcpcapstone.meettoticket.project.dto.ProjectSaveRequest;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final UserRepository users;
    private final RepositoryPermissionGateway permissions;
    private final TransactionTemplate tx;

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
    // 저장은 한 트랜잭션으로 끝내고, Agent 호출은 트랜잭션 밖에서 해 DB 연결을 붙잡지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProjectDetail create(ProjectSaveRequest request, Long userId) {
        Long projectId = tx.execute(status -> {
            String owner = request.githubOwner().trim();
            String repo = request.githubRepo().trim();
            if (projects.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(owner, repo).isPresent())
                throw new BusinessException(ErrorCode.DUPLICATE_PROJECT_REPOSITORY);
            Project project = projects.save(new Project(request.name().trim(), owner, repo));
            apply(project, request);
            User creator = users.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
            members.save(new ProjectMember(project, creator, creator.getName(), creator.getGithubName(), ProjectRole.ADMIN));
            saveMembers(project, request.memberUserIds());
            return project.getId();
        });
        return checkAndLoad(projectId, userId);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProjectDetail update(Long projectId, ProjectSaveRequest request, Long userId) {
        tx.executeWithoutResult(status -> {
            requireAdmin(projectId, userId);
            Project project = projects.getReferenceById(projectId);
            projects.findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(request.githubOwner().trim(), request.githubRepo().trim())
                    .filter(other -> !other.getId().equals(projectId))
                    .ifPresent(other -> { throw new BusinessException(ErrorCode.DUPLICATE_PROJECT_REPOSITORY); });
            apply(project, request);
            saveMembers(project, request.memberUserIds());
        });
        return checkAndLoad(projectId, userId);
    }

    private record CheckTarget(Long memberId, String githubName) {}

    // 저장소나 멤버 구성이 바뀌었을 수 있으므로 저장할 때마다 기존 결과를 지우고 GitHub 계정이 있는 멤버를 다시 확인한다.
    // 전체 실패는 저장 자체를 막지 않고 해당 멤버 권한 null, connections.github=ERROR로 남긴다.
    private ProjectDetail checkAndLoad(Long projectId, Long userId) {
        record Plan(String owner, String repo, List<CheckTarget> targets) {}
        Plan plan = tx.execute(status -> {
            Project project = projects.getReferenceById(projectId);
            project.recordGithubConnection(null);
            List<CheckTarget> targets = new ArrayList<>();
            for (ProjectMember m : members.findByProjectIdOrderById(projectId)) {
                m.recordPermission(null);
                if (m.getGithubName() != null) targets.add(new CheckTarget(m.getId(), m.getGithubName()));
            }
            return new Plan(project.getGithubOwner(), project.getGithubRepo(), targets);
        });
        if (plan.targets().isEmpty()) return loadDetail(projectId, userId);

        List<String> names = plan.targets().stream().map(CheckTarget::githubName).distinct().toList();
        Map<String, String> results = new HashMap<>();
        try {
            for (var r : permissions.check(plan.owner(), plan.repo(), names))
                if (r.githubName() != null) results.put(r.githubName().toLowerCase(Locale.ROOT), r.permission());
        } catch (RuntimeException e) {
            log.warn("프로젝트 {} 멤버 저장소 권한 확인 실패: {}", projectId, e.getMessage());
            results.clear();
        }

        tx.executeWithoutResult(status -> {
            boolean error = results.isEmpty();
            for (CheckTarget t : plan.targets()) {
                ProjectMember m = members.findById(t.memberId()).orElse(null);
                // 확인하는 사이 GitHub 계정이 바뀐 멤버는 결과를 적용하지 않는다.
                if (m == null || !t.githubName().equals(m.getGithubName())) continue;
                ProjectDetail.RepositoryPermission permission = toPermission(results.get(t.githubName().toLowerCase(Locale.ROOT)));
                m.recordPermission(permission);
                if (permission == null) error = true;
            }
            projects.getReferenceById(projectId)
                    .recordGithubConnection(error ? ProjectDetail.ConnectionStatus.ERROR : ProjectDetail.ConnectionStatus.CONNECTED);
        });
        return loadDetail(projectId, userId);
    }

    // GRANTED/DENIED만 저장한다. ERROR나 알 수 없는 값, 응답에서 빠진 멤버는 null(확인 실패)이다.
    private static ProjectDetail.RepositoryPermission toPermission(String value) {
        if ("GRANTED".equals(value)) return ProjectDetail.RepositoryPermission.GRANTED;
        if ("DENIED".equals(value)) return ProjectDetail.RepositoryPermission.DENIED;
        return null;
    }

    private ProjectDetail loadDetail(Long projectId, Long userId) {
        return tx.execute(status -> detail(projects.getReferenceById(projectId), userId));
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
    private void saveMembers(Project project, List<Long> memberUserIds) {
        Set<Long> wanted = new LinkedHashSet<>(memberUserIds);
        Map<Long, User> found =
                users.findAllById(wanted).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        if (found.size() != wanted.size()) throw new BusinessException(ErrorCode.MEMBER_USER_NOT_FOUND);

        List<ProjectMember> current = members.findByProjectIdOrderById(project.getId());
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

    // 외부 호출 없이 저장된 값만 반환한다. 저장소 권한과 GitHub 연결은 프로젝트 저장 때 확인한 결과다 (확인 전이면 null).
    // Notion/Slack 연결은 아직 확인하지 않는다 (TASK-006, #139).
    private ProjectDetail detail(Project project, Long userId) {
        List<ProjectMember> all = members.findByProjectIdOrderById(project.getId());
        List<ProjectDetail.Member> registered = all.stream()
                .filter(m -> m.getUser() != null)
                .map(m -> new ProjectDetail.Member(
                        m.getUser().getId(),
                        m.getUser().getName(),
                        m.getUser().getGithubName(),
                        m.isAdmin(),
                        m.getUser().getGithubName() == null
                                ? ProjectDetail.RepositoryPermission.NO_ACCOUNT
                                : m.getRepositoryPermission()))
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
                new ProjectDetail.Connections(project.getGithubConnection(), unchecked(project.getNotionPageUrl()), unchecked(project.getSlackChannelId())),
                canEdit);
    }

    private static ProjectDetail.ConnectionStatus unchecked(String value) {
        return value == null ? ProjectDetail.ConnectionStatus.NOT_CONNECTED : null;
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
