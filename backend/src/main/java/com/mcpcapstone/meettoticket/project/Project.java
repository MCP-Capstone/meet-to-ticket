package com.mcpcapstone.meettoticket.project;

import jakarta.persistence.*;

import lombok.*;

@Entity
@Table(
        name = "projects",
        uniqueConstraints = @UniqueConstraint(columnNames = {"github_owner", "github_repo"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "project_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(name = "github_owner", nullable = false, length = 100)
    private String githubOwner;

    @Column(name = "github_repo", nullable = false, length = 100)
    private String githubRepo;

    @Column(name = "notion_page_url", length = 500)
    private String notionPageUrl;

    @Column(name = "slack_channel_id", length = 30)
    private String slackChannelId;

    public Project(String name, String owner, String repo) {
        this.name = name;
        this.githubOwner = owner;
        this.githubRepo = repo;
    }

    public void update(
            String name,
            String description,
            String owner,
            String repo,
            String notionPageUrl,
            String slackChannelId) {
        this.name = name;
        this.description = description;
        this.githubOwner = owner;
        this.githubRepo = repo;
        this.notionPageUrl = notionPageUrl;
        this.slackChannelId = slackChannelId;
    }
}
