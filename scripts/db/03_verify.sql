-- mock 프로필로 JPA 초기화 직후 전용 DB에서 실행하는 읽기 전용 검증.
BEGIN READ ONLY;
DO $verify$
DECLARE
    p_id bigint;
BEGIN
    IF (SELECT count(*) FROM users) <> 1
       OR (SELECT count(*) FROM projects) <> 1
       OR (SELECT count(*) FROM project_members) <> 6
       OR (SELECT count(*) FROM meetings) <> 3 THEN
        RAISE EXCEPTION '초기 데이터 건수 불일치: users=1, projects=1, project_members=6, meetings=3이어야 합니다';
    END IF;
    SELECT project_id INTO STRICT p_id FROM projects
    WHERE github_owner='MCP-Capstone' AND github_repo='mcp-capstone';
    IF EXISTS (
        SELECT 1 FROM meetings m
        WHERE m.project_id <> p_id
           OR json_typeof(m.participants_json) <> 'array'
           OR json_array_length(m.participants_json) <> 6
           OR (SELECT count(DISTINCT v::bigint)
               FROM json_array_elements_text(m.participants_json) a(v)) <> 6
           OR EXISTS (
               SELECT 1 FROM json_array_elements_text(m.participants_json) a(v)
               LEFT JOIN project_members pm ON pm.project_member_id=v::bigint AND pm.project_id=m.project_id
               WHERE pm.project_member_id IS NULL
           )
    ) THEN RAISE EXCEPTION '회의 참여자 ID 또는 프로젝트 연결 불일치'; END IF;
    IF EXISTS (SELECT 1 FROM analysis_tasks) OR EXISTS (SELECT 1 FROM task_items) THEN
        RAISE EXCEPTION '초기 상태에서 분석 작업 및 업무 결과는 비어 있어야 합니다';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM users u JOIN project_members pm ON pm.user_id=u.user_id
        WHERE u.login_id='demo' AND u.name='임나윤' AND pm.name='임나윤'
          AND pm.github_name='jadoolim' AND pm.project_id=p_id
    ) THEN RAISE EXCEPTION '테스트 계정과 멤버 연결 불일치'; END IF;
END;
$verify$;
SELECT 'users' AS table_name, count(*) AS row_count FROM users
UNION ALL SELECT 'projects', count(*) FROM projects
UNION ALL SELECT 'project_members', count(*) FROM project_members
UNION ALL SELECT 'meetings', count(*) FROM meetings
UNION ALL SELECT 'analysis_tasks', count(*) FROM analysis_tasks
UNION ALL SELECT 'task_items', count(*) FROM task_items;
SELECT pm.name, pm.github_name, u.login_id
FROM project_members pm LEFT JOIN users u ON u.user_id=pm.user_id
ORDER BY pm.project_member_id;
SELECT title, meeting_date, participants_json, original_filename FROM meetings ORDER BY meeting_date;
COMMIT;
