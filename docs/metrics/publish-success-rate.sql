-- 발행 성공률 (#264). 정의와 한계는 docs/metrics/README.md. PostgreSQL 전용.
-- 티켓 단위의 현재 상태 기준(최종 성공률)이다. 실패 뒤 다시 시도해 성공하면 성공으로 센다.
-- 성공: PUBLISHED, LINKED, 알림 댓글까지 끝난 UPDATED.
-- 실패: FAILED, 알림 댓글이 실패한 UPDATED (publish_error_code가 남아 있다. 이슈 본문은 갱신됐지만 확인이 필요하다).
-- 분모 = 성공 + 실패. 제외: SKIPPED(발행 생략, skipped로 따로 센다), PUBLISHING(진행 중), 아직 발행하지 않은 상태.
-- task_items만 쓰므로 V4 이전 티켓도 현재 상태로 센다.
-- 반환: project_id별 한 줄과 전체 한 줄(project_id가 null).
SELECT
	m.project_id,
	count(*) FILTER (WHERE i.ticket_status IN ('PUBLISHED', 'LINKED')
		OR (i.ticket_status = 'UPDATED' AND i.publish_error_code IS NULL)) AS succeeded,
	count(*) FILTER (WHERE i.ticket_status = 'FAILED'
		OR (i.ticket_status = 'UPDATED' AND i.publish_error_code IS NOT NULL)) AS failed,
	count(*) FILTER (WHERE i.ticket_status IN ('PUBLISHED', 'LINKED', 'UPDATED', 'FAILED')) AS attempted,
	round(
		(count(*) FILTER (WHERE i.ticket_status IN ('PUBLISHED', 'LINKED')
			OR (i.ticket_status = 'UPDATED' AND i.publish_error_code IS NULL)))::numeric
			/ NULLIF(count(*) FILTER (WHERE i.ticket_status IN ('PUBLISHED', 'LINKED', 'UPDATED', 'FAILED')), 0),
		4) AS publish_success_rate,
	count(*) FILTER (WHERE i.ticket_status = 'SKIPPED') AS skipped
FROM task_items i
JOIN analysis_tasks t ON t.analysis_task_id = i.task_id
JOIN meetings m ON m.meeting_id = t.meeting_id
GROUP BY ROLLUP (m.project_id)
ORDER BY m.project_id NULLS LAST;
