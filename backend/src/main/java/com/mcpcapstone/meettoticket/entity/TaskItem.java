package com.mcpcapstone.meettoticket.entity;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "task_items", indexes = {@Index(name = "idx_task_items_task", columnList = "task_id"), @Index(name = "idx_task_items_work_key", columnList = "work_key")})
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TaskItem {
    // Agent가 생성한 itemId를 할당한다.
    @Id
    @Column(name = "task_item_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private AnalysisTask task;

    @Column(name = "work_key", nullable = true, length = 100)
    private String workKey;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> content;

    // DBML 필드를 보존한다. 이번 흐름은 CREATE만 사용한다.
    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "reason", nullable = true, columnDefinition = "text")
    private String reason;

    @Column(name = "review_status", nullable = false, length = 30)
    private String reviewStatus;

    @Column(name = "publish_status", nullable = false, length = 30)
    private String publishStatus;

    @Column(name = "github_issue_number")
    private Integer githubIssueNumber;

    @Column(name = "github_issue_url", nullable = true, columnDefinition = "text")
    private String githubIssueUrl;

    @Column(name = "error_message", nullable = true, columnDefinition = "text")
    private String errorMessage;

}
