package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.meeting.Meeting;

import jakarta.persistence.*;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "analysis_tasks", indexes = @Index(columnList = "meeting_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisTask {
    @Id
    @Column(name = "analysis_task_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 100)
    private TaskStage stage;

    @Column(name = "input_json", nullable = false, columnDefinition = "text")
    private String inputJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    // 진행 화면용 세부 정보 JSON (단계, 단계별 건수). Agent가 보고한 마지막 값
    @Column(name = "progress_json", columnDefinition = "text")
    private String progressJson;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    public AnalysisTask(UUID id, Meeting meeting, String inputJson) {
        this.id = id;
        this.meeting = meeting;
        this.inputJson = inputJson;
        this.status = TaskStatus.QUEUED;
        this.createdAt = OffsetDateTime.now();
    }

    public void update(
            TaskStatus status, TaskStage stage, OffsetDateTime finishedAt, String error) {
        this.status = status;
        this.stage = stage;
        this.finishedAt = finishedAt;
        this.errorMessage = error;
    }

    public void updateProgress(String progressJson) {
        this.progressJson = progressJson;
    }
}
