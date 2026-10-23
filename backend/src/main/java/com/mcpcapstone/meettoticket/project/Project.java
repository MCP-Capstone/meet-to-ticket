package com.mcpcapstone.meettoticket.project;

import jakarta.persistence.*;

import lombok.*;

import com.mcpcapstone.meettoticket.project.dto.ProjectDetail.ConnectionStatus;

import java.util.Objects;

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

	@Enumerated(EnumType.STRING)
	@Column(name = "notion_connection_status", length = 20)
	private ConnectionStatus notionConnectionStatus;

    @Column(name = "slack_channel_id", length = 30)
    private String slackChannelId;

	@Enumerated(EnumType.STRING)
	@Column(name = "slack_connection_status", length = 20)
	private ConnectionStatus slackConnectionStatus;

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
		updateNotionPageUrl(notionPageUrl);
		updateSlackChannelId(slackChannelId);
    }

	// 프로젝트 설정이 바뀌면 이전 외부 확인 결과는 무효다. Agent 확인 전에는 null로 둔다.
	private void updateNotionPageUrl(String notionPageUrl) {
		if (!Objects.equals(this.notionPageUrl, notionPageUrl)) {
			this.notionPageUrl = notionPageUrl;
			this.notionConnectionStatus = notionPageUrl == null ? ConnectionStatus.NOT_CONNECTED : null;
		}
	}

	private void updateSlackChannelId(String slackChannelId) {
		if (!Objects.equals(this.slackChannelId, slackChannelId)) {
			this.slackChannelId = slackChannelId;
			this.slackConnectionStatus = slackChannelId == null ? ConnectionStatus.NOT_CONNECTED : null;
		}
	}

	public ConnectionStatus notionConnectionStatus() {
		return notionPageUrl == null ? ConnectionStatus.NOT_CONNECTED : notionConnectionStatus;
	}

	public ConnectionStatus slackConnectionStatus() {
		return slackChannelId == null ? ConnectionStatus.NOT_CONNECTED : slackConnectionStatus;
	}

	public void updateNotionConnectionStatus(ConnectionStatus status) {
		this.notionConnectionStatus = notionPageUrl == null ? ConnectionStatus.NOT_CONNECTED : status;
	}

	public void updateSlackConnectionStatus(ConnectionStatus status) {
		this.slackConnectionStatus = slackChannelId == null ? ConnectionStatus.NOT_CONNECTED : status;
	}
}
