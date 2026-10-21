package com.mcpcapstone.meettoticket.entity;


import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "analysis_tasks", indexes = @Index(name = "idx_analysis_tasks_meeting", columnList = "meeting_id"))
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AnalysisTask {
    // Spring 서비스에서 UUID를 생성해 할당한다.
    @Id
    @Column(name = "analysis_task_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "stage", nullable = true, length = 100)
    private String stage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_json", nullable = false, columnDefinition = "json")
    private Map<String, Object> input;

    @Column(name = "error_message", nullable = true, columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp")
    private LocalDateTime createdAt;

    @Column(name = "finished_at", columnDefinition = "timestamp")
    private LocalDateTime finishedAt;

}
