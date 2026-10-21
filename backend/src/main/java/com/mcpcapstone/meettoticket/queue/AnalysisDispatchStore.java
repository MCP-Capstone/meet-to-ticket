package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.analysis.AnalysisStore;
import com.mcpcapstone.meettoticket.analysis.AnalysisTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalysisDispatchStore {
	private final AnalysisDispatchRepository dispatches;
	private final AnalysisTaskRepository tasks;
	private final AnalysisStore analyses;

	@Transactional
	public void enqueue(UUID id, String mode) {
		dispatches.saveAndFlush(new AnalysisDispatch(id, mode));
	}

	public AnalysisDispatch.State state(UUID id) {
		return dispatches.findById(id).map(AnalysisDispatch::getState).orElse(null);
	}

	/** 오래된 접수 claim만 조회 대상으로 전환한다. HTTP POST는 다시 하지 않는다. */
	@Transactional
	public AnalysisDispatch.State pollState(UUID id, OffsetDateTime staleBefore) {
		var dispatch = dispatches.findForUpdate(id).orElse(null);
		if (dispatch == null) return null; // 큐 도입 전 작업은 기존 방식으로 조회한다.
		if (dispatch.getState() == AnalysisDispatch.State.SUBMITTING && stale(dispatch, staleBefore))
			dispatch.state(AnalysisDispatch.State.UNCERTAIN);
		return dispatch.getState();
	}

	private boolean stale(AnalysisDispatch dispatch, OffsetDateTime cutoff) {
		return dispatch.getUpdatedAt() == null || !dispatch.getUpdatedAt().isAfter(cutoff);
	}

	public List<UUID> unpublished() {
		return dispatches.findTop100ByPublishedFalseAndState(AnalysisDispatch.State.QUEUED)
			.stream().map(AnalysisDispatch::getTaskId).toList();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void published(UUID id) {
		dispatches.findForUpdate(id).ifPresent(AnalysisDispatch::published);
	}

	@Transactional
	public boolean claim(UUID id, String mode) {
		var dispatch = dispatches.findForUpdate(id).orElseThrow();
		if (dispatch.getState() != AnalysisDispatch.State.QUEUED) return false;
		if (terminal(id)) return false;
		if (!dispatch.getExecutionMode().equals(mode)) {
			exhaust(dispatch, "AGENT_MODE_CHANGED: 실행 모드가 변경돼 기존 작업을 재실행하지 않았습니다.");
			return false;
		}
		dispatch.state(AnalysisDispatch.State.SUBMITTING);
		return true;
	}

	public boolean terminal(UUID id) {
		return tasks.findById(id).orElseThrow().getStatus().terminal();
	}

	@Transactional
	public void attempted(UUID id) { dispatches.findForUpdate(id).orElseThrow().attempted(); }

	@Transactional
	public void finish(UUID id, AnalysisDispatch.State state) {
		dispatches.findForUpdate(id).orElseThrow().state(state);
	}

	@Transactional
	public void exhausted(UUID id) {
		exhaust(dispatches.findForUpdate(id).orElseThrow(),
			"AGENT_ACCEPTANCE_FAILED: 최초 접수와 재시도 3회가 실패했습니다. DLQ를 확인하세요.");
	}

	/** DLQ 보관 메시지의 복사본으로 DB 복구 후 실패 상태를 기록한다. */
	@Transactional
	public void failFromDeadLetter(UUID id) {
		if (!tasks.existsById(id)) return;
		var dispatch = dispatches.findForUpdate(id).orElse(null);
		var task = tasks.findForUpdate(id).orElseThrow();
		if (task.getStatus().terminal()) return; // 이미 저장된 결과·실패 사유를 보존한다.
		if (dispatch != null) dispatch.state(AnalysisDispatch.State.EXHAUSTED);
		analyses.fail(id, "QUEUE_PROCESSING_FAILED: 큐 처리 중 오류가 발생했습니다. DLQ와 Agent 접수 여부를 확인하세요.");
	}

	private void exhaust(AnalysisDispatch dispatch, String message) {
		dispatch.state(AnalysisDispatch.State.EXHAUSTED);
		analyses.fail(dispatch.getTaskId(), message);
	}
}
