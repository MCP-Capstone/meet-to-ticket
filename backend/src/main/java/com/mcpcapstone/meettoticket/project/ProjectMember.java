package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.project.dto.ProjectDetail;
import com.mcpcapstone.meettoticket.user.User;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(
        name = "project_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}),
        indexes = @Index(columnList = "project_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProjectMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_member_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "github_name", length = 100)
    private String githubName;

    // 기존 행은 컬럼이 추가될 때 MEMBER로 채운다.
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'MEMBER'")
    @Column(nullable = false, length = 10)
    private ProjectRole role = ProjectRole.MEMBER;

    // GRANTED/DENIED만 저장한다. NO_ACCOUNT는 githubName이 없으면 응답에서 채우고, 확인 전이나 확인 실패는 null이다.
    @Enumerated(EnumType.STRING)
    @Column(name = "repository_permission", length = 20)
    private ProjectDetail.RepositoryPermission repositoryPermission;

    public ProjectMember(Project project, User user, String name, String githubName) {
        this(project, user, name, githubName, ProjectRole.MEMBER);
    }

    public ProjectMember(
            Project project, User user, String name, String githubName, ProjectRole role) {
        this.project = project;
        this.user = user;
        this.name = name;
        this.githubName = githubName;
        this.role = role;
    }

    public boolean isAdmin() {
        return role == ProjectRole.ADMIN;
    }

    public void makeAdmin() {
        this.role = ProjectRole.ADMIN;
    }

    public void changeRole(ProjectRole role) {
        this.role = role;
    }

    public void recordPermission(ProjectDetail.RepositoryPermission permission) {
        this.repositoryPermission = permission;
    }

    // 가입 사용자 멤버는 저장할 때마다 사용자 정보의 이름과 GitHub 계정으로 맞춘다.
    public void syncWithUser() {
        if (user == null) return;
        this.name = user.getName();
        this.githubName = user.getGithubName();
    }
}
