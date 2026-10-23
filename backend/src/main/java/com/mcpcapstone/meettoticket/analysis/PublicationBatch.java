package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.user.User;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "publication_batches", indexes = @Index(columnList = "created_by_user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicationBatch {
    @Id
    @Column(name = "publication_batch_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_user_id", nullable = false)
    private User createdBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PublicationBatchStatus status;

    @Column(name = "error_code", length = 100)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public PublicationBatch(UUID id, User createdBy) {
        this.id = id;
        this.createdBy = createdBy;
        this.status = PublicationBatchStatus.RUNNING;
        this.createdAt = OffsetDateTime.now();
    }

    public void markAcceptanceUncertain(String message) {
        if (!status.terminal()) {
            this.status = PublicationBatchStatus.ACCEPTANCE_UNCERTAIN;
            this.errorCode = "AGENT_ACCEPTANCE_UNKNOWN";
            this.errorMessage = message;
        }
    }

    public void rejectBeforeAgent(String message) {
        if (!status.terminal()) {
            this.status = PublicationBatchStatus.FAILED;
            this.errorCode = "AGENT_ACCEPTANCE_REJECTED";
            this.errorMessage = message;
            this.finishedAt = OffsetDateTime.now();
        }
    }

    public void complete(PublicationBatchStatus status, String errorCode, String errorMessage) {
        if (this.status.terminal()) return;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
    }
}
