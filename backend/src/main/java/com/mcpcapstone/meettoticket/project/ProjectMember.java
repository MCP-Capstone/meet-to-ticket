package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.user.User;

import jakarta.persistence.*;

import lombok.*;

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

    public ProjectMember(Project project, User user, String name, String githubName) {
        this.project = project;
        this.user = user;
        this.name = name;
        this.githubName = githubName;
    }
}
