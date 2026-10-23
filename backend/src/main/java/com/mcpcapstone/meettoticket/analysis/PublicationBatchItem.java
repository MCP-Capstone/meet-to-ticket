package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "publication_batch_items", uniqueConstraints = @UniqueConstraint(columnNames = {"publication_batch_id", "task_item_id"}), indexes = @Index(columnList = "task_item_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicationBatchItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "publication_batch_item_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "publication_batch_id", nullable = false)
    private PublicationBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_item_id", nullable = false)
    private TaskItem ticket;

    @Column(name = "result_status", length = 30)
    private String resultStatus;

    @Column(name = "processing_type", length = 10)
    private String processingType;

    @Column(name = "github_issue_number")
    private Integer githubIssueNumber;

    @Column(name = "github_issue_url", length = 2048)
    private String githubIssueUrl;

    @Column(name = "comment_url", length = 2048)
    private String commentUrl;

    @Column(name = "message_url", length = 2048)
    private String messageUrl;

    @Column(name = "notion_page_id", length = 100)
    private String notionPageId;

    @Column(name = "notion_page_url", length = 2048)
    private String notionPageUrl;

    @Column(name = "dropped_labels_json", columnDefinition = "text")
    private String droppedLabelsJson;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(nullable = false)
    private boolean retryable;

    public PublicationBatchItem(PublicationBatch batch, TaskItem ticket) {
        this.batch = batch;
        this.ticket = ticket;
    }

    public void record(PublicationResult result, String droppedLabelsJson) {
        if (resultStatus != null) return; // Agent의 콜백 재전송은 멱등하다.
        this.resultStatus = result.status();
        this.processingType = result.processingType();
        this.githubIssueNumber = result.githubIssueNumber();
        this.githubIssueUrl = result.githubIssueUrl();
        this.commentUrl = result.commentUrl();
        this.messageUrl = result.messageUrl();
        this.notionPageId = result.notionPageId();
        this.notionPageUrl = result.notionPageUrl();
        this.droppedLabelsJson = droppedLabelsJson;
        this.errorCode = result.errorCode();
        this.errorMessage = result.errorMessage();
        this.retryable = result.retryable();
    }

    public record PublicationResult(
            String status,
            String processingType,
            Integer githubIssueNumber,
            String githubIssueUrl,
            String commentUrl,
            String messageUrl,
            String notionPageId,
            String notionPageUrl,
            java.util.List<String> droppedLabels,
            String errorCode,
            String errorMessage,
            boolean retryable) {}
}
