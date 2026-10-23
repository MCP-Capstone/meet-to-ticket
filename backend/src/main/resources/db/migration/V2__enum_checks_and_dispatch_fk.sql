-- V2-1. enum 컬럼의 check 제약을 현재 enum 값으로 맞춘다. (EnumCheckConstraintSync 대체)
-- ddl-auto: update로 만들어진 DB는 V1을 건너뛰고 오래된 check 제약을 가질 수 있다.
-- 해당 컬럼 하나만 거는 check 제약을 모두 지우고 <table>_<column>_check 이름으로 다시 만든다.
-- V1로 만든 DB에서는 같은 제약을 다시 만들 뿐이라 결과가 같다.
DO $$
DECLARE
	spec record;
	old record;
BEGIN
	FOR spec IN
		SELECT * FROM (VALUES
			('users', 'slack_connection_status', 'CONNECTED,NOT_CONNECTED,ERROR'),
			('projects', 'notion_connection_status', 'CONNECTED,NOT_CONNECTED,ERROR'),
			('projects', 'github_connection', 'CONNECTED,NOT_CONNECTED,ERROR'),
			('projects', 'slack_connection_status', 'CONNECTED,NOT_CONNECTED,ERROR'),
			('project_members', 'role', 'ADMIN,MEMBER'),
			('project_members', 'repository_permission', 'GRANTED,DENIED,NO_ACCOUNT'),
			('analysis_tasks', 'status', 'QUEUED,RUNNING,COMPLETED,PARTIALLY_FAILED,FAILED'),
			('analysis_tasks', 'stage', 'EXTRACTING,NORMALIZING,REVIEWING,PUBLISHING,VERIFYING'),
			('publish_batches', 'status', 'RUNNING,SUCCEEDED,PARTIAL_FAILED,FAILED'),
			('task_items', 'ticket_status', 'ASSIGNEE_REQUIRED,PENDING_REVIEW,CONFIRMED,PUBLISHING,REJECTED,PUBLISHED,LINKED,UPDATED,FAILED'),
			('task_items', 'processing_type', 'CREATE,LINK,UPDATE'),
			('task_items', 'publish_target', 'GITHUB,SLACK,NOTION'),
			('analysis_dispatches', 'state', 'QUEUED,SUBMITTING,ACCEPTED,UNCERTAIN,EXHAUSTED'),
			('project_reindexes', 'status', 'RUNNING,SUCCEEDED,FAILED')
		) AS t (tbl, col, vals)
	LOOP
		FOR old IN
			SELECT c.conname
			FROM pg_constraint c
			JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attname = spec.col
			WHERE c.conrelid = to_regclass(spec.tbl)
				AND c.contype = 'c'
				AND c.conkey = ARRAY[a.attnum]
		LOOP
			EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', spec.tbl, old.conname);
		END LOOP;
		EXECUTE format(
			'ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IN (%s))',
			spec.tbl,
			spec.tbl || '_' || spec.col || '_check',
			spec.col,
			(SELECT string_agg(quote_literal(v), ', ') FROM unnest(string_to_array(spec.vals, ',')) AS v));
	END LOOP;
END
$$;

-- V2-2. analysis_dispatches.task_id -> analysis_tasks FK.
-- 기존 DB에는 task 없이 남은 고아 dispatch 행이 있을 수 있다.
-- NOT VALID로 먼저 추가해 새 행부터 검사하고, 고아 행을 지운 뒤 VALIDATE로 기존 행을 검증한다.
-- 고아 행은 접수 기록만 남고 작업이 없는 행이라 지워도 재실행이 일어나지 않는다.
ALTER TABLE analysis_dispatches
	ADD CONSTRAINT fk_analysis_dispatches_task
	FOREIGN KEY (task_id) REFERENCES analysis_tasks (analysis_task_id) NOT VALID;

DELETE FROM analysis_dispatches d
WHERE NOT EXISTS (SELECT 1 FROM analysis_tasks t WHERE t.analysis_task_id = d.task_id);

ALTER TABLE analysis_dispatches VALIDATE CONSTRAINT fk_analysis_dispatches_task;
