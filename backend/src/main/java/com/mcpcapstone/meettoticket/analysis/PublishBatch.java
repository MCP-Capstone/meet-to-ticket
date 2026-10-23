package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.user.User;

import jakarta.persistence.*;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

// "확인한 N건 발행" 한 번의 요청. 결과는 Agent가 /internal/publish-batches/{batchId}/result로 보고한다.
@Entity
@Table(name = "publish_batches", indexes = @Index(columnList = "user_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublishBatch {
    @Id
    @Column(name = "publish_batch_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PublishBatchStatus status;

    @Column(name = "execution_mode", nullable = false, length = 10)
    private String executionMode;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    public PublishBatch(User user, String executionMode) {
        this.id = UUID.randomUUID();
        this.user = user;
        this.status = PublishBatchStatus.RUNNING;
        this.executionMode = executionMode;
        this.createdAt = OffsetDateTime.now();
    }

    public void finish(PublishBatchStatus status, String errorMessage) {
        this.status = status;
        this.errorMessage = errorMessage;
        this.finishedAt = OffsetDateTime.now();
    }
}
