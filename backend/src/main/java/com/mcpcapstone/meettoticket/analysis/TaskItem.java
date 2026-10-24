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

    // 일괄 발행 결과 (TASK-046). 이슈 번호, URL, 사유는 위의 githubIssueNumber 등을 함께 쓴다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "publish_batch_id")
    private PublishBatch publishBatch;

    @Column(name = "publish_error_code", length = 50)
    private String publishErrorCode;

    @ColumnDefault("false")
    @Column(name = "publish_retryable", nullable = false)
    private boolean publishRetryable;

    // 요청은 갔지만 결과를 모르는 경우. 다시 시도하면 이슈가 두 번 생길 수 있어 구분해 둔다.
    @ColumnDefault("false")
    @Column(name = "publish_outcome_unknown", nullable = false)
    private boolean publishOutcomeUnknown;

    // 지난 발행이 결과 모름으로 끝났던 적이 있는지. 수정으로 되돌려 결과 칸을 비워도 남겨 두고,
    // 다시 발행할 때 Agent가 이미 반영된 이슈, 댓글, 섹션이 있는지 반드시 대조하게 한다. 확실한 성공이 오면 지운다.
    @ColumnDefault("false")
    @Column(name = "previous_outcome_unknown", nullable = false)
    private boolean previousOutcomeUnknown;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'GITHUB'")
    @Column(name = "publish_target", nullable = false, length = 10)
    private PublishTarget publishTarget = PublishTarget.GITHUB;

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
        this.publishTarget = PublishTarget.from(item.contentJson());
        // GitHub 이외의 대상은 항상 신규 발행이다 (REQ-015)
        this.processingType = publishTarget == PublishTarget.GITHUB
                ? ProcessingType.from(item.action(), item.contentJson())
                : ProcessingType.CREATE;
    }

    // 담당 멤버가 정해지면 검토 대기, 아니면 담당 지정 필요.
    public void assignTo(ProjectMember member) {
        this.assignee = member;
        this.ticketStatus = member == null ? TicketStatus.ASSIGNEE_REQUIRED : TicketStatus.PENDING_REVIEW;
    }

    // 담당자가 카드에서 고친 내용을 반영한다. 담당 멤버는 바꾸지 않으면 null을 넘긴다.
    public void edit(ProjectMember newAssignee, String contentJson) {
        if (newAssignee != null) this.assignee = newAssignee;
        this.contentJson = contentJson;
    }

    // 담당자가 카드에서 발행 대상과 처리 유형을 바꾼다 (REQ-015). 규칙 검사는 TicketService가 한다.
    public void changePublication(PublishTarget target, ProcessingType type) {
        this.publishTarget = target;
        this.processingType = type;
    }

    // 실패한 티켓을 수정하려고 검토 대기로 되돌린다. 지난 발행 결과는 비우되 결과 모름은 previousOutcomeUnknown으로 남긴다.
    // 결과 모름이 남아 있으면 발행 대상은 바꿀 수 없다(TicketService.changePublication, #280).
    // 지난 묶음의 결과 조회는 묶음이 끝날 때 저장한 기록(PublishBatch.entriesJson)을 쓰므로 여기서 비워도 깨지지 않는다.
    public void reviseFailed() {
        this.previousOutcomeUnknown = this.previousOutcomeUnknown || this.publishOutcomeUnknown;
        this.ticketStatus = TicketStatus.PENDING_REVIEW;
        this.publishBatch = null;
        this.publishErrorCode = null;
        this.publishRetryable = false;
        this.publishOutcomeUnknown = false;
        this.errorMessage = null;
        this.githubIssueNumber = null;
        this.githubIssueUrl = null;
    }

    // 재대조할 수 없는 대상(Notion, Slack)의 결과 모름 실패를 담당자가 확인한 결과로 정리한다 (#280).
    // Agent가 결과 모름 뒤 재발행을 막으므로, 이 확인이 없으면 티켓이 실패에서 빠져나오지 못한다.
    // 반영됐으면 발행됨으로 끝내고(대상이 신규 생성만 되므로), 반영되지 않았으면 결과 모름을 풀어 다시 시도할 수 있게 한다.
    public void resolveUnknownOutcome(boolean applied, String url) {
        // 확인 결과는 지금 실패가 결과 모름 자체인지(타임아웃, *_VERIFY_REQUIRED)에 따라 사유를 바꾼다.
        // 결과 모름 뒤의 재발행이 설정 오류(NOTION_ROOT_MISSING 등)로 끝났다면 그 사유는 그대로 남긴다.
        boolean aboutUnknown = publishOutcomeUnknown || VERIFY_REQUIRED_CODES.contains(publishErrorCode);
        this.previousOutcomeUnknown = false;
        this.publishOutcomeUnknown = false;
        if (applied) {
            this.ticketStatus = TicketStatus.PUBLISHED;
            // 실패한 묶음과의 연결을 끊는다. 지난 묶음의 결과 조회는 묶음에 저장한 기록을 쓴다.
            this.publishBatch = null;
            this.githubIssueNumber = null;
            this.githubIssueUrl = url;
            this.publishErrorCode = null;
            this.errorMessage = null;
            this.publishRetryable = false;
        } else {
            if (aboutUnknown) {
                this.publishErrorCode = VERIFIED_NOT_APPLIED;
                this.errorMessage = "대상 도구에 반영되지 않은 것을 확인했습니다. 다시 시도하세요.";
                this.publishRetryable = true;
            }
            this.githubIssueNumber = null;
            this.githubIssueUrl = null;
        }
    }

    public static final String VERIFIED_NOT_APPLIED = "VERIFIED_NOT_APPLIED";

    // Agent가 결과 모름 뒤 재발행을 막을 때 쓰는 코드 (agent/batch_publish.py _VERIFY_REQUIRED와 같은 이름).
    private static final java.util.Set<String> VERIFY_REQUIRED_CODES = java.util.Set.of("NOTION_VERIFY_REQUIRED", "SLACK_VERIFY_REQUIRED");

    // 결과 모름이 남아 있는지. 다시 발행할 때 Agent가 재대조(GitHub)하거나 막는다(Notion, Slack).
    // 발행 대상을 잠그므로(#280) 결과 모름은 항상 현재 대상의 것이다.
    public boolean mustRecheckOnPublish() {
        return previousOutcomeUnknown || publishOutcomeUnknown;
    }

    // 다시 시도 버튼을 열어도 되는 실패인지. Agent가 재시도 가능하다고 알린 실패와, 지금 실패가 결과 모름인 경우다.
    // 결과 모름(RESULT_TIMEOUT, RESULT_MISSING, 묶음 전체 실패 등)은 담당자가 확인한 뒤 다시 시도하면 GitHub는 재대조하고,
    // 재대조할 수 없는 대상(Notion, Slack)은 PublishResolution.MANUAL_CHECK가 먼저 막는다.
    public boolean canRetryPublish() {
        return publishRetryable || publishOutcomeUnknown;
    }

    public void moveTo(TicketStatus status) {
        this.ticketStatus = status;
    }

    // 확인한 티켓(또는 재시도하는 실패 티켓)을 일괄 발행에 담는다. 결과가 올 때까지 다시 담을 수 없다.
    // 이전 실패 사유는 접수가 취소될 때 되살려야 해서 남겨 두고, 결과가 오면 덮어쓴다.
    public void startPublishing(PublishBatch batch) {
        this.publishBatch = batch;
        this.ticketStatus = TicketStatus.PUBLISHING;
    }

    // Agent가 접수하지 않은 것이 확실하면 담기 전 상태(확인 또는 실패)로 돌려놓는다.
    public void cancelPublishing() {
        this.publishBatch = null;
		this.ticketStatus = isCommentRetry() ? TicketStatus.UPDATED
				: this.publishErrorCode != null ? TicketStatus.FAILED : TicketStatus.CONFIRMED;
	}

	public boolean isCommentRetry() {
		return publishTarget == PublishTarget.GITHUB && processingType == ProcessingType.UPDATE
				&& "COMMENT_FAILED".equals(publishErrorCode) && githubIssueNumber != null
				&& (ticketStatus == TicketStatus.UPDATED || ticketStatus == TicketStatus.PUBLISHING);
    }

    public void recordPublishResult(
            TicketStatus status,
            ProcessingType executed,
            Integer issueNumber,
            String issueUrl,
            String errorCode,
            String message,
            boolean retryable,
            boolean outcomeUnknown) {
        // 확실한 성공이 오면 지난 결과 모름은 풀린다. 실패는 지난 결과 모름을 이어 간다.
        this.previousOutcomeUnknown = status == TicketStatus.FAILED && mustRecheckOnPublish();
        this.ticketStatus = status;
        if (executed != null) this.processingType = executed;
        this.githubIssueNumber = issueNumber;
        this.githubIssueUrl = issueUrl;
        this.publishErrorCode = errorCode;
        this.errorMessage = message;
        this.publishRetryable = retryable;
        this.publishOutcomeUnknown = outcomeUnknown;
    }
}
