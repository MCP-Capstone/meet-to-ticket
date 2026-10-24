-- 승인율 (#264). 정의와 한계는 docs/metrics/README.md. PostgreSQL 전용.
-- 현재 결정이 승인(CONFIRM)인 티켓 / 현재 결정이 있는 티켓(승인 + 거절).
-- 현재 결정 = 결정 계열 전이(CONFIRM, REJECT, REOPEN, RESTORE, REVISE) 중 가장 마지막.
-- REOPEN/RESTORE/REVISE가 마지막이면 다시 검토 대기라 결정 없음으로 보고 분모에서 뺀다.
-- 반환: project_id별 한 줄과 전체 한 줄(project_id가 null).
-- 기간으로 좁히려면 last_decision의 WHERE에 h.created_at 범위를 더한다 (그 기간 안의 마지막 결정 기준이 된다).
WITH last_decision AS (
	SELECT DISTINCT ON (h.task_item_id) h.task_item_id, h.reason
	FROM ticket_status_history h
	WHERE h.reason IN ('CONFIRM', 'REJECT', 'REOPEN', 'RESTORE', 'REVISE')
	ORDER BY h.task_item_id, h.created_at DESC, h.ticket_status_history_id DESC
)
SELECT
	m.project_id,
	count(*) FILTER (WHERE d.reason = 'CONFIRM') AS approved,
	count(*) FILTER (WHERE d.reason = 'REJECT') AS rejected,
	count(*) FILTER (WHERE d.reason IN ('CONFIRM', 'REJECT')) AS decided,
	round(
		(count(*) FILTER (WHERE d.reason = 'CONFIRM'))::numeric
			/ NULLIF(count(*) FILTER (WHERE d.reason IN ('CONFIRM', 'REJECT')), 0),
		4) AS approval_rate
FROM last_decision d
JOIN task_items i ON i.task_item_id = d.task_item_id
JOIN analysis_tasks t ON t.analysis_task_id = i.task_id
JOIN meetings m ON m.meeting_id = t.meeting_id
GROUP BY ROLLUP (m.project_id)
ORDER BY m.project_id NULLS LAST;
