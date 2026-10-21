package com.mcpcapstone.meettoticket.queue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.UUID;
import java.time.OffsetDateTime;

/** Agent POST의 중복 실행을 막는 영속 접수 기록이자 큐 발행 outbox. */
@Entity
@Table(name = "analysis_dispatches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisDispatch {
	public enum State { QUEUED, SUBMITTING, ACCEPTED, UNCERTAIN, EXHAUSTED }

	@Id
	private UUID taskId;
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private State state;
	@Column(nullable = false)
	private boolean published;
	@Column(nullable = false)
	private int attempts;
	@Column(nullable = false, length = 10)
	private String executionMode;
	// nullable: 기존 SUBMITTING 행도 DB 초기화 없이 복구할 수 있다.
	@Column(name = "updated_at")
	private OffsetDateTime updatedAt;

	public AnalysisDispatch(UUID taskId, String executionMode) {
		this.taskId = taskId;
		this.executionMode = executionMode;
		this.state = State.QUEUED;
		this.updatedAt = OffsetDateTime.now();
	}

	void published() { published = true; }
	void state(State state) {
		this.state = state;
		this.updatedAt = OffsetDateTime.now();
	}
	void attempted() {
		attempts++;
		this.updatedAt = OffsetDateTime.now();
	}
}
