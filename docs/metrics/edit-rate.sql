-- 항목 수정률과 필드별 수정률 (#264). 정의와 한계는 docs/metrics/README.md. PostgreSQL 전용(jsonb).
-- 분모: 현재 결정이 승인(CONFIRM)이고 V4 이후에 만들어진 티켓.
-- 분자: 에이전트 원본(AGENT rev=1)과 최종본(가장 마지막 AGENT/USER_EDIT 리비전)을 비교해
--       제목, 담당자, 기한, 본문 중 1개 이상이 다른 티켓. 글자 diff는 쓰지 않고 값이 같은지만 본다.
-- USER_EDIT 리비전이 있다는 사실만으로는 수정으로 세지 않는다 (발행 대상, 처리 유형, 그 밖의 본문 키만 바뀐 수정본이 있다).
-- 반환: project_id별 한 줄과 전체 한 줄(project_id가 null).
WITH last_decision AS (
	SELECT DISTINCT ON (h.task_item_id) h.task_item_id, h.reason
	FROM ticket_status_history h
	WHERE h.reason IN ('CONFIRM', 'REJECT', 'REOPEN', 'RESTORE', 'REVISE')
	ORDER BY h.task_item_id, h.created_at DESC, h.ticket_status_history_id DESC
),
approved AS (
	SELECT d.task_item_id
	FROM last_decision d
	WHERE d.reason = 'CONFIRM'
		-- V4 이후 생성 티켓만: 생성 전이(from_status가 null)는 백필하지 않으므로 V4 이전 티켓에는 없다.
		-- V4 이전 티켓의 AGENT 리비전은 백필한 현재 값이라 원본이 아니다.
		AND EXISTS (
			SELECT 1 FROM ticket_status_history c
			WHERE c.task_item_id = d.task_item_id AND c.from_status IS NULL)
),
agent AS (
	SELECT r.task_item_id, r.snapshot
	FROM task_item_revisions r
	WHERE r.kind = 'AGENT' AND r.rev = 1
),
final_rev AS (
	SELECT DISTINCT ON (r.task_item_id) r.task_item_id, r.snapshot
	FROM task_item_revisions r
	WHERE r.kind IN ('AGENT', 'USER_EDIT')
	ORDER BY r.task_item_id, r.rev DESC
),
diff AS (
	SELECT
		m.project_id,
		-- 제목, 본문은 양끝 공백을 지우고, 기한은 없음과 빈 문자열을 같게 본다 (카드 수정 API가 이렇게 저장한다).
		COALESCE(btrim(a.snapshot ->> 'title'), '')
			IS DISTINCT FROM COALESCE(btrim(f.snapshot ->> 'title'), '') AS title_changed,
		(a.snapshot ->> 'assignee_member_id')
			IS DISTINCT FROM (f.snapshot ->> 'assignee_member_id') AS assignee_changed,
		(COALESCE(btrim(a.snapshot -> 'content' -> 'due' ->> 'text'), ''),
		 COALESCE(btrim(a.snapshot -> 'content' -> 'due' ->> 'date'), ''),
		 COALESCE(btrim(a.snapshot -> 'content' -> 'due' ->> 'time'), ''))
			IS DISTINCT FROM
		(COALESCE(btrim(f.snapshot -> 'content' -> 'due' ->> 'text'), ''),
		 COALESCE(btrim(f.snapshot -> 'content' -> 'due' ->> 'date'), ''),
		 COALESCE(btrim(f.snapshot -> 'content' -> 'due' ->> 'time'), '')) AS due_changed,
		COALESCE(btrim(a.snapshot -> 'content' ->> 'description'), '')
			IS DISTINCT FROM COALESCE(btrim(f.snapshot -> 'content' ->> 'description'), '') AS description_changed
	FROM approved ap
	JOIN agent a ON a.task_item_id = ap.task_item_id
	JOIN final_rev f ON f.task_item_id = ap.task_item_id
	JOIN task_items i ON i.task_item_id = ap.task_item_id
	JOIN analysis_tasks t ON t.analysis_task_id = i.task_id
	JOIN meetings m ON m.meeting_id = t.meeting_id
)
SELECT
	project_id,
	count(*) AS approved_items,
	count(*) FILTER (WHERE title_changed OR assignee_changed OR due_changed OR description_changed) AS edited_items,
	round((count(*) FILTER (WHERE title_changed OR assignee_changed OR due_changed OR description_changed))::numeric
		/ NULLIF(count(*), 0), 4) AS edit_rate,
	count(*) FILTER (WHERE title_changed) AS title_edited,
	round((count(*) FILTER (WHERE title_changed))::numeric / NULLIF(count(*), 0), 4) AS title_edit_rate,
	count(*) FILTER (WHERE assignee_changed) AS assignee_edited,
	round((count(*) FILTER (WHERE assignee_changed))::numeric / NULLIF(count(*), 0), 4) AS assignee_edit_rate,
	count(*) FILTER (WHERE due_changed) AS due_edited,
	round((count(*) FILTER (WHERE due_changed))::numeric / NULLIF(count(*), 0), 4) AS due_edit_rate,
	count(*) FILTER (WHERE description_changed) AS description_edited,
	round((count(*) FILTER (WHERE description_changed))::numeric / NULLIF(count(*), 0), 4) AS description_edit_rate
FROM diff
GROUP BY ROLLUP (project_id)
ORDER BY project_id NULLS LAST;
