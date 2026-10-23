package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.project.ProjectMember;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.ColumnDefault;

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

    // 담당 멤버. 담당자가 없거나 여러 명이거나 프로젝트 멤버와 연결되지 않으면 null (REQ-010).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_member_id")
    private ProjectMember assignee;

    // 기존 행은 컬럼이 추가될 때 담당 지정 필요로 채운다.
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ASSIGNEE_REQUIRED'")
    @Column(name = "ticket_status", nullable = false, length = 30)
    private TicketStatus ticketStatus = TicketStatus.ASSIGNEE_REQUIRED;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'CREATE'")
    @Column(name = "processing_type", nullable = false, length = 10)
    private ProcessingType processingType = ProcessingType.CREATE;

    // 담당자가 카드에서 고친 값 {"body": 본문, "due": {"date", "time"}}. 고친 칸만 들어 있고, 없으면 Agent 결과를 쓴다.
    // 제목과 담당 멤버는 각 칸을 바로 고친다 (REQ-014, TASK-028). 일괄 발행은 고친 값을 쓴다.
    @Column(name = "edited_json", columnDefinition = "text")
    private String editedJson;

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
        this.processingType = ProcessingType.fromAgentAction(item.action());
    }

    // 카드 수정. 상태 확인은 TicketReviewService가 한다.
    public void edit(String title, String editedJson, ProjectMember assignee) {
        this.title = title;
        this.editedJson = editedJson;
        this.assignee = assignee;
    }

    // 확인, 거절, 다시 열기, 되돌리기. 허용되는 전이인지는 TicketReviewService가 확인한다 (기획 4.2).
    public void changeStatus(TicketStatus status) {
        this.ticketStatus = status;
    }

    // 담당 멤버가 정해지면 검토 대기, 아니면 담당 지정 필요.
    public void assignTo(ProjectMember member) {
        this.assignee = member;
        this.ticketStatus = member == null ? TicketStatus.ASSIGNEE_REQUIRED : TicketStatus.PENDING_REVIEW;
    }
}
