package com.mcpcapstone.meettoticket.queue;

import com.mcpcapstone.meettoticket.analysis.AnalysisStore;
import com.mcpcapstone.meettoticket.analysis.AnalysisTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

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

	private void exhaust(AnalysisDispatch dispatch, String message) {
		dispatch.state(AnalysisDispatch.State.EXHAUSTED);
		analyses.fail(dispatch.getTaskId(), message);
	}
}
