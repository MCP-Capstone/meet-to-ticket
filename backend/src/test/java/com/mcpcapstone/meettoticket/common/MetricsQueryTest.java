package com.mcpcapstone.meettoticket.common;

import static com.mcpcapstone.meettoticket.common.MigrationSchemaTest.connect;
import static com.mcpcapstone.meettoticket.common.MigrationSchemaTest.exec;
import static com.mcpcapstone.meettoticket.common.MigrationSchemaTest.flyway;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * docs/metrics/*.sql 지표 쿼리를 실제 PostgreSQL에서 고정 데이터로 실행해 기대 수치를 검증한다 (#264).
 * 쿼리는 jsonb 연산자를 쓰는 PostgreSQL 전용이라 H2 테스트로는 돌릴 수 없다. MigrationSchemaTest와 같이
 * MIGRATION_TEST_DB_URL이 있을 때만 실행하고, 전용 스키마(mig_metrics)를 만들고 지운다.
 *
 * 입력 데이터는 TicketHistoryRecorder가 쌓는 모양(docs/revisions-and-history.md)을 SQL로 직접 넣는다.
 * 서비스 흐름이 이 모양을 쌓는지는 TicketHistoryTest(H2)가 검증한다.
 */
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_DB_URL", matches = ".+")
class MetricsQueryTest {
	static final String SCHEMA = "mig_metrics";
	static final Path DIR = Path.of("..", "docs", "metrics");
	static final String TASK_1 = "00000000-0000-0000-0000-0000000000c1";
	static final String TASK_2 = "00000000-0000-0000-0000-0000000000c2";

	@BeforeEach
	@AfterEach
	void cleanSchema() throws SQLException {
		exec("public", "drop schema if exists " + SCHEMA + " cascade");
	}

	static String sql(String file) throws Exception {
		return Files.readString(DIR.resolve(file));
	}

	// 컬럼 이름 -> 값 문자열. 줄마다 "project_id=1 approved=8 ..." 형태로 읽는다.
	static List<String> run(String file) throws Exception {
		List<String> rows = new ArrayList<>();
		try (Connection c = connect(SCHEMA); Statement s = c.createStatement(); var rs = s.executeQuery(sql(file))) {
			var meta = rs.getMetaData();
			while (rs.next()) {
				StringBuilder row = new StringBuilder();
				for (int i = 1; i <= meta.getColumnCount(); i++) {
					if (i > 1) row.append(' ');
					row.append(meta.getColumnLabel(i)).append('=').append(rs.getString(i));
				}
				rows.add(row.toString());
			}
		}
		return rows;
	}

	static String id(int n) {
		return "00000000-0000-0000-0000-0000000001%02d".formatted(n);
	}

	String content(String description, String dueText, String dueDate) {
		return "{\"description\":%s,\"due\":{\"text\":%s,\"date\":%s,\"time\":null},\"tasks\":[\"a\"]}"
			.formatted(quote(description), quote(dueText), quote(dueDate));
	}

	static String quote(String v) {
		return v == null ? "null" : "\"" + v + "\"";
	}

	void ticket(int n, String task, String status, String errorCode) throws SQLException {
		exec(SCHEMA, """
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status, publish_error_code)
			values ('%s', '%s', 't%d', '{}', 'CREATE', 'PENDING', 'DRAFT', '%s', %s)"""
			.formatted(id(n), task, n, status, errorCode == null ? "null" : "'" + errorCode + "'"));
	}

	// from_status가 null이면 생성 전이다. second는 같은 시각이라도 순서를 지키는지 보려고 번호 순서로만 쌓는다.
	void history(int n, String from, String to, String reason, int minute) throws SQLException {
		exec(SCHEMA, """
			insert into ticket_status_history (task_item_id, from_status, to_status, reason, created_at)
			values ('%s', %s, '%s', '%s', timestamptz '2026-10-22 10:00:00+09' + interval '%d minutes')"""
			.formatted(id(n), from == null ? "null" : "'" + from + "'", to, reason, minute));
	}

	void revision(int n, int rev, String kind, String title, Integer assignee, String target, String content) throws SQLException {
		exec(SCHEMA, """
			insert into task_item_revisions (task_item_id, rev, kind, snapshot, schema_version, created_at)
			values ('%s', %d, '%s', jsonb_build_object('title', %s::text, 'assignee_member_id', %s::bigint, 'publish_target', '%s'::text,
				'processing_type', 'CREATE'::text, 'content', '%s'::jsonb), 1, now())"""
			.formatted(id(n), rev, kind, "'" + title + "'", assignee == null ? "null" : assignee.toString(), target, content));
	}

	// 에이전트가 만든 티켓: AGENT 리비전과 생성 전이, 검토 대기 -> 확정.
	void created(int n, String task, String status, String errorCode, Integer assignee, String content) throws SQLException {
		ticket(n, task, status, errorCode);
		revision(n, 1, "AGENT", "t" + n, assignee, "GITHUB", content);
		history(n, null, "PENDING_REVIEW", "AGENT_RESULT", 0);
	}

	void confirm(int n, int minute) throws SQLException {
		history(n, "PENDING_REVIEW", "CONFIRMED", "CONFIRM", minute);
	}

	void seed() throws Exception {
		exec("public", "create schema " + SCHEMA);
		flyway(SCHEMA).migrate();
		exec(SCHEMA, """
			insert into users (login_id, password_hash, name, created_at) values ('u', 'h', 'n', now());
			insert into projects (name, github_owner, github_repo) values ('p1', 'o', 'r1'), ('p2', 'o', 'r2');
			insert into meetings (project_id, created_by, title, meeting_date, participants_json, original_filename, original_content)
				select p.project_id, u.user_id, 't', current_date, '[]', 'f.txt', 'c' from projects p, users u;
			insert into analysis_tasks (analysis_task_id, meeting_id, status, input_json, created_at)
				select '%s', min(meeting_id), 'COMPLETED', '{}', now() from meetings;
			insert into analysis_tasks (analysis_task_id, meeting_id, status, input_json, created_at)
				select '%s', max(meeting_id), 'COMPLETED', '{}', now() from meetings"""
			.formatted(TASK_1, TASK_2));

		String base = content("본문", "내일", "2026-10-23");

		// 1: 원본 그대로 승인, 발행 성공
		created(1, TASK_1, "PUBLISHED", null, 7, base);
		confirm(1, 1);

		// 2: 제목과 기한을 고쳐 승인 (title, due), 갱신 완료
		created(2, TASK_1, "UPDATED", null, 7, base);
		revision(2, 2, "USER_EDIT", "t2 수정", 7, "GITHUB", content("본문", "모레", "2026-10-24"));
		confirm(2, 1);

		// 3: 본문만 고침 (description). 제목 끝 공백, 기한 null -> 빈 문자열은 수정이 아니다. 발행 실패.
		created(3, TASK_1, "FAILED", "GITHUB_FORBIDDEN", 7, content("본문", null, null));
		revision(3, 2, "USER_EDIT", "t3 ", 7, "GITHUB", content("본문 보강", "", ""));
		confirm(3, 1);

		// 4: 담당자만 바꿈 (assignee) + 발행 대상 변경(KPI 필드 아님). 알림 댓글 실패 UPDATED.
		created(4, TASK_1, "UPDATED", "COMMENT_FAILED", null, base);
		revision(4, 2, "USER_EDIT", "t4", 8, "NOTION", base);
		confirm(4, 1);

		// 5: USER_EDIT가 있지만 발행 대상만 바뀜: 수정이 아니다. 발행 성공.
		created(5, TASK_1, "PUBLISHED", null, 7, base);
		revision(5, 2, "USER_EDIT", "t5", 7, "SLACK", base);
		confirm(5, 1);

		// 6: 거절
		created(6, TASK_1, "REJECTED", null, 7, base);
		history(6, "PENDING_REVIEW", "REJECTED", "REJECT", 1);

		// 7: 확정했다가 다시 열어 검토 대기: 결정 없음
		created(7, TASK_1, "PENDING_REVIEW", null, 7, base);
		confirm(7, 1);
		history(7, "CONFIRMED", "PENDING_REVIEW", "REOPEN", 2);

		// 8: 승인했지만 발행 생략: 성공도 실패도 아니다
		created(8, TASK_1, "SKIPPED", null, 7, base);
		confirm(8, 1);

		// 9: V4 이전 티켓 (생성 전이 없음, 백필한 AGENT 리비전만): 수정률에서 제외, 승인율/성공률에는 포함
		ticket(9, TASK_1, "PUBLISHED", null);
		revision(9, 1, "AGENT", "t9 이미 수정됨", 7, "GITHUB", content("이미 고친 본문", "내일", "2026-10-23"));
		confirm(9, 1);

		// 10: 발행 진행 중: 성공률 분모에서 제외
		created(10, TASK_1, "PUBLISHING", null, 7, base);
		confirm(10, 1);
		history(10, "CONFIRMED", "PUBLISHING", "PUBLISH_REQUESTED", 2);

		// 11: 실패했다가 수정하러 되돌렸고 아직 다시 확정하지 않음: 결정 없음
		created(11, TASK_1, "PENDING_REVIEW", null, 7, base);
		confirm(11, 1);
		history(11, "CONFIRMED", "PUBLISHING", "PUBLISH_REQUESTED", 2);
		history(11, "PUBLISHING", "FAILED", "GITHUB_FORBIDDEN", 3);
		history(11, "FAILED", "PENDING_REVIEW", "REVISE", 4);

		// 12: 거절했다가 되돌리고 다시 승인: 마지막 결정(승인)이 기준
		created(12, TASK_1, "PUBLISHED", null, 7, base);
		history(12, "PENDING_REVIEW", "REJECTED", "REJECT", 1);
		history(12, "REJECTED", "PENDING_REVIEW", "RESTORE", 2);
		confirm(12, 3);

		// 다른 프로젝트: 원본 그대로 승인, 발행 성공
		created(21, TASK_2, "LINKED", null, 9, base);
		confirm(21, 1);
	}

	@Test
	void approvalRateUsesTheLastDecisionOfEachTicket() throws Exception {
		seed();
		// p1 승인 1,2,3,4,5,8,9,10,12 = 9건, 거절 6 = 1건. 7(다시 열기), 11(수정하러 되돌림)은 결정 없음.
		// p2 승인 1건. 전체 승인 10, 거절 1.
		assertThat(run("approval-rate.sql")).containsExactly(
			"project_id=1 approved=9 rejected=1 decided=10 approval_rate=0.9000",
			"project_id=2 approved=1 rejected=0 decided=1 approval_rate=1.0000",
			"project_id=null approved=10 rejected=1 decided=11 approval_rate=0.9091");
	}

	@Test
	void editRateComparesAgentSnapshotWithTheFinalSnapshotOnFourFields() throws Exception {
		seed();
		// p1 분모(승인 + V4 이후 티켓): 1,2,3,4,5,8,10,12 = 8건 (9는 V4 이전이라 제외).
		// 수정: 2(제목, 기한), 3(본문), 4(담당자) = 3건. 5는 발행 대상만 바뀌어 수정이 아니다.
		assertThat(run("edit-rate.sql")).containsExactly(
			"project_id=1 approved_items=8 edited_items=3 edit_rate=0.3750"
				+ " title_edited=1 title_edit_rate=0.1250 assignee_edited=1 assignee_edit_rate=0.1250"
				+ " due_edited=1 due_edit_rate=0.1250 description_edited=1 description_edit_rate=0.1250",
			"project_id=2 approved_items=1 edited_items=0 edit_rate=0.0000"
				+ " title_edited=0 title_edit_rate=0.0000 assignee_edited=0 assignee_edit_rate=0.0000"
				+ " due_edited=0 due_edit_rate=0.0000 description_edited=0 description_edit_rate=0.0000",
			"project_id=null approved_items=9 edited_items=3 edit_rate=0.3333"
				+ " title_edited=1 title_edit_rate=0.1111 assignee_edited=1 assignee_edit_rate=0.1111"
				+ " due_edited=1 due_edit_rate=0.1111 description_edited=1 description_edit_rate=0.1111");
	}

	@Test
	void publishSuccessRateExcludesSkippedInFlightAndCountsCommentFailureAsNotSucceeded() throws Exception {
		seed();
		// p1 성공: 1(PUBLISHED), 2(UPDATED), 5(PUBLISHED), 9(PUBLISHED), 12(PUBLISHED) = 5
		// p1 실패: 3(FAILED), 4(UPDATED + COMMENT_FAILED) = 2. 분모 7.
		// 제외: 8(SKIPPED, skipped로 따로 센다), 10(PUBLISHING), 6/7/11(발행 전).
		assertThat(run("publish-success-rate.sql")).containsExactly(
			"project_id=1 succeeded=5 failed=2 attempted=7 publish_success_rate=0.7143 skipped=1",
			"project_id=2 succeeded=1 failed=0 attempted=1 publish_success_rate=1.0000 skipped=0",
			"project_id=null succeeded=6 failed=2 attempted=8 publish_success_rate=0.7500 skipped=1");
	}

	@Test
	void ratesAreNullWhenThereIsNothingToDivideBy() throws Exception {
		exec("public", "create schema " + SCHEMA);
		flyway(SCHEMA).migrate();
		// 데이터가 없어도 0으로 나누는 오류 없이 전체 한 줄만 나오고 비율은 null이다.
		assertThat(run("approval-rate.sql")).containsExactly(
			"project_id=null approved=0 rejected=0 decided=0 approval_rate=null");
		assertThat(run("edit-rate.sql")).containsExactly(
			"project_id=null approved_items=0 edited_items=0 edit_rate=null"
				+ " title_edited=0 title_edit_rate=null assignee_edited=0 assignee_edit_rate=null"
				+ " due_edited=0 due_edit_rate=null description_edited=0 description_edit_rate=null");
		assertThat(run("publish-success-rate.sql")).containsExactly(
			"project_id=null succeeded=0 failed=0 attempted=0 publish_success_rate=null skipped=0");
	}
}
