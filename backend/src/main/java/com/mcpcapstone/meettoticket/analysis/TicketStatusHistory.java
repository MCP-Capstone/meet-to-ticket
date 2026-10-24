package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

// ticket_status가 바뀔 때마다 한 줄씩 쌓는다. 한 번 쌓으면 고치지 않는다 (V4, #264).
@Entity
@Table(
        name = "ticket_status_history",
        indexes = @Index(name = "idx_ticket_status_history_task_item_id_created_at", columnList = "task_item_id, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TicketStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_status_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "task_item_id", nullable = false)
    private TaskItem taskItem;

    // 티켓이 처음 만들어질 때만 null
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private TicketStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 30)
    private TicketStatus toStatus;

    // 전이를 일으킨 사용자. 에이전트 결과, 발행 결과 반영처럼 시스템이 바꾼 경우는 null.
    @Column(name = "actor_user_id")
    private Long actorUserId;

    // 전이 원인 코드 (TicketHistoryRecorder의 REASON_* 상수, 발행 실패는 오류 코드)
    @Column(length = 100)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public TicketStatusHistory(TaskItem taskItem, TicketStatus from, TicketStatus to, Long actorUserId, String reason) {
        this.taskItem = taskItem;
        this.fromStatus = from;
        this.toStatus = to;
        this.actorUserId = actorUserId;
        this.reason = reason;
        this.createdAt = OffsetDateTime.now();
    }
}
