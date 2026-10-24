package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.UUID;

// 티켓 하나가 대상 하나에 하는 외부 쓰기 한 건의 기록 (V5, #263). unique(target, idempotency_key)가 같은 발행의 중복 예약을 막는다.
// 상태 전이는 PublicationRepository의 조건부 UPDATE로만 하므로 이 엔티티에는 setter가 없다. 읽기용이다.
@Entity
@Table(
        name = "publications",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "publications_target_idempotency_key_key",
                        columnNames = {"target", "idempotency_key"}),
        indexes = {
            @Index(name = "idx_publications_task_item_id", columnList = "task_item_id"),
            @Index(name = "idx_publications_publish_batch_id", columnList = "publish_batch_id"),
            @Index(name = "idx_publications_status_updated_at", columnList = "status, updated_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Publication {
    // 멱등키 형식 버전. 키 구성을 바꾸면 올려서 옛 키와 섞이지 않게 한다.
    public static final String KEY_VERSION = "v1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "publication_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "task_item_id", nullable = false)
    private TaskItem taskItem;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PublishTarget target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PublishOperation operation;

    @Column(name = "idempotency_key", nullable = false, length = 200)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PublicationStatus status;

    // 예약한 횟수. 처음 예약이 1이고, FAILED(또는 확인된 UNCERTAIN)에서 새 시도를 시작할 때마다 1 늘어난다.
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    // 마지막 시도를 보낸 발행 묶음. FK는 걸지 않는다 (actor_user_id와 같은 이유로, 기록은 묶음과 독립이다).
    @Column(name = "publish_batch_id")
    private UUID publishBatchId;

    // GitHub 이슈 번호 또는 Notion 페이지 ID. Slack은 id가 없어 비어 있다.
    @Column(name = "external_id", length = 255)
    private String externalId;

    @Column(name = "external_url", length = 2048)
    private String externalUrl;

    // 실패, 결과 모름의 원인 코드. 확정(CONFIRMED)에서는 본문은 반영됐지만 보조 작업(알림 댓글)이 실패한 코드가 남을 수 있다.
    @Column(name = "error_code", length = 50)
    private String errorCode;

    // 이 키가 한 번이라도 UNCERTAIN이 됐고 아직 확인되지 않았는지 (V6). 재예약해도 유지되고, CONFIRMED나 담당자의 반영 안 됨 확인에서만 풀린다.
    // 이 표시가 있으면 확정 실패, 접수 취소, 발행 생략으로 끝나도 FAILED로 되돌리지 않고 UNCERTAIN을 유지한다.
    @ColumnDefault("false")
    @Column(name = "outcome_unknown", nullable = false)
    private boolean outcomeUnknown;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // 마지막으로 RESERVED가 된 시각. 멈춘 예약을 찾는 기준이다 (복구 워커).
    @Column(name = "reserved_at", nullable = false)
    private OffsetDateTime reservedAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // 같은 논리적 발행은 같은 키다: 티켓 + 대상 + 작업. 티켓은 대상마다 하나의 외부 항목만 만들므로 내용 수정 이력은 키에 넣지 않는다.
    public static String keyOf(UUID ticketId, PublishTarget target, PublishOperation operation) {
        return KEY_VERSION + ":" + ticketId + ":" + target.name() + ":" + operation.name();
    }
}
