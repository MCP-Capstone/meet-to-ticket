package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.*;

import lombok.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

// 티켓의 시점별 전체 스냅샷. 한 번 쌓으면 고치지 않는다 (V4, #264).
@Entity
@Table(
        name = "task_item_revisions",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "task_item_revisions_task_item_id_rev_key",
                        columnNames = {"task_item_id", "rev"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TaskItemRevision {
    // 스냅샷 JSON의 형식 버전. 키를 바꾸면 올린다.
    public static final int SCHEMA_VERSION = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_item_revision_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(name = "task_item_id", nullable = false)
    private TaskItem taskItem;

    // 티켓 안에서 1부터 늘어나는 번호
    @Column(nullable = false)
    private int rev;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RevisionKind kind;

    // 제목, 담당 멤버, 발행 대상, 처리 유형, 본문(content) 전체. PUBLISHED는 발행 결과(result)도 담는다.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String snapshot;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    // Agent가 결과에 실어 보내지 않아 지금은 비어 있다. 보내기 시작하면 AGENT 리비전에 채운다.
    @Column(name = "prompt_version", length = 64)
    private String promptVersion;

    @Column(length = 100)
    private String model;

    // 수정/발행을 한 사용자. 에이전트가 만든 AGENT 리비전과 시스템이 확정한 값은 null.
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    public TaskItemRevision(TaskItem taskItem, int rev, RevisionKind kind, String snapshot, Long actorUserId) {
        this.taskItem = taskItem;
        this.rev = rev;
        this.kind = kind;
        this.snapshot = snapshot;
        this.schemaVersion = SCHEMA_VERSION;
        this.actorUserId = actorUserId;
        this.createdAt = OffsetDateTime.now();
    }
}
