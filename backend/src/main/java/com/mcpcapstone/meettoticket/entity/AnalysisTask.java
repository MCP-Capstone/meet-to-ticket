package com.mcpcapstone.meettoticket.entity;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.mcpcapstone.meettoticket.entity.enums.AnalysisStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private AnalysisStatus status;

	@Column(name = "stage", nullable = true, length = 100)
	private String stage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "input_json", nullable = false, columnDefinition = "json")
	private Map<String, Object> input;

	@Column(name = "error_message", nullable = true, columnDefinition = "text")
	private String errorMessage;

	@CreationTimestamp
	@ColumnDefault("now()")
	@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamp with time zone")
	private OffsetDateTime createdAt;

	@Column(name = "finished_at", columnDefinition = "timestamp with time zone")
	private OffsetDateTime finishedAt;

}
