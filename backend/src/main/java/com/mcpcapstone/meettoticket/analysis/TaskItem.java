package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.*;

import lombok.*;

import java.util.UUID;

@Entity
@Table(
        name = "task_items",
        indexes = {@Index(columnList = "task_id"), @Index(columnList = "work_key")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskItem {
    @Id
    @Column(name = "task_item_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private AnalysisTask task;

    @Column(name = "work_key", length = 100)
    private String workKey;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(name = "content_json", nullable = false, columnDefinition = "text")
    private String contentJson;

    @Column(nullable = false, length = 30)
    private String action;

    @Column(columnDefinition = "text")
    private String reason;

    @Column(name = "review_status", nullable = false, length = 30)
    private String reviewStatus;

    @Column(name = "publish_status", nullable = false, length = 30)
    private String publishStatus;

    @Column(name = "github_issue_number")
    private Integer githubIssueNumber;

    @Column(name = "github_issue_url", length = 2048)
    private String githubIssueUrl;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    public TaskItem(
            AnalysisTask task,
            com.mcpcapstone.meettoticket.agent.AgentContracts.Item item,
            String json) {
        this.id = item.itemId();
        this.task = task;
        this.workKey = item.workKey();
        this.title = item.title();
        this.contentJson = json;
        this.action = item.action();
        this.reason = item.reason();
        this.reviewStatus = item.reviewStatus();
        this.publishStatus = item.publishStatus();
        this.githubIssueNumber = item.githubIssueNumber();
        this.githubIssueUrl = item.githubIssueUrl();
        this.errorMessage = item.errorMessage();
    }
}
