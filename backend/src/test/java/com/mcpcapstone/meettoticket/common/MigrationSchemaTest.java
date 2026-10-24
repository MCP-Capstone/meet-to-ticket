package com.mcpcapstone.meettoticket.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mcpcapstone.meettoticket.BackendApplication;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Flyway 마이그레이션이 엔티티가 만드는 스키마와 같은지 실제 PostgreSQL에서 검증한다.
 * 마이그레이션은 PostgreSQL 전용 SQL이라 H2 테스트에서는 돌릴 수 없다. 접속 정보가 있을 때만 실행한다.
 * (CI에서 접속 정보가 빠지면 MigrationTestEnvironmentGuardTest가 실패한다)
 *
 * <pre>
 * MIGRATION_TEST_DB_URL=jdbc:postgresql://localhost:5432/postgres ./gradlew test --tests '*MigrationSchemaTest'
 * </pre>
 *
 * 사용자 / 비밀번호는 MIGRATION_TEST_DB_USERNAME / MIGRATION_TEST_DB_PASSWORD(기본 meettoticket)다.
 * 테스트용 스키마(mig_*)를 만들고 지우므로 개발 데이터가 든 DB를 가리키지 않는다.
 * 각 테스트는 자기 스키마를 직접 만들고 다른 테스트의 결과에 기대지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_DB_URL", matches = ".+")
class MigrationSchemaTest {
	static final String BASE_URL = System.getenv("MIGRATION_TEST_DB_URL");
	static final String USER = envOr("MIGRATION_TEST_DB_USERNAME", "meettoticket");
	static final String PASSWORD = envOr("MIGRATION_TEST_DB_PASSWORD", "meettoticket");
	static final String HIBERNATE = "mig_hibernate";
	static final String FRESH = "mig_fresh";
	static final String LEGACY = "mig_legacy";
	static final String TASK = "00000000-0000-0000-0000-000000000001";
	static final String ORPHAN = "00000000-0000-0000-0000-0000000000ff";

	static String envOr(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value;
	}

	static String url(String schema) {
		return BASE_URL + (BASE_URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
	}

	static Connection connect(String schema) throws SQLException {
		return DriverManager.getConnection(url(schema), USER, PASSWORD);
	}

	static void exec(String schema, String sql) throws SQLException {
		try (Connection c = connect(schema); Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	static List<String> query(String schema, String sql) throws SQLException {
		List<String> rows = new ArrayList<>();
		try (Connection c = connect(schema); Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
			while (rs.next()) rows.add(rs.getString(1));
		}
		return rows;
	}

	static Flyway flyway(String schema) {
		// application.yml의 spring.flyway 설정과 같게 맞춘다.
		return Flyway.configure()
			.dataSource(url(schema), USER, PASSWORD)
			.schemas(schema)
			.locations("classpath:db/migration")
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.load();
	}

	@BeforeEach
	@AfterEach
	void cleanSchemas() throws SQLException {
		for (String schema : List.of(HIBERNATE, FRESH, LEGACY)) exec("public", "drop schema if exists " + schema + " cascade");
		for (String schema : List.of(HIBERNATE, FRESH, LEGACY)) exec("public", "create schema " + schema);
	}

	// 컬럼 타입 / NULL / 기본값 / identity, 제약, 인덱스를 이름 없이 비교할 수 있는 문자열로 만든다.
	// 마이그레이션에만 있는 보관 테이블(analysis_dispatches_orphans)은 제외한다.
	static List<String> describe(String schema) throws SQLException {
		List<String> rows = new ArrayList<>(query(schema, """
			select 'col ' || c.relname || '.' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
				|| ' notnull=' || a.attnotnull || ' identity=' || a.attidentity::text
				|| ' default=' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')
			from pg_attribute a
			join pg_class c on c.oid = a.attrelid and c.relkind = 'r'
			join pg_namespace n on n.oid = c.relnamespace and n.nspname = '%s'
			left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
			where a.attnum > 0 and not a.attisdropped
				and c.relname not in ('flyway_schema_history', 'analysis_dispatches_orphans')
			""".formatted(schema)));
		rows.addAll(query(schema, """
			select 'con ' || c.relname || ' ' || con.contype::text || ' ' || pg_get_constraintdef(con.oid)
			from pg_constraint con
			join pg_class c on c.oid = con.conrelid
			join pg_namespace n on n.oid = c.relnamespace and n.nspname = '%s'
			where c.relname not in ('flyway_schema_history', 'analysis_dispatches_orphans')
			""".formatted(schema)));
		rows.addAll(query(schema, """
			select 'idx ' || regexp_replace(indexdef, 'INDEX \\S+ ON ' || schemaname || '\\.', 'INDEX ON ')
			from pg_indexes
			where schemaname = '%s' and tablename not in ('flyway_schema_history', 'analysis_dispatches_orphans')
			""".formatted(schema)));
		return rows.stream().sorted().toList();
	}

	static final String DISPATCH_FK = "con analysis_dispatches f FOREIGN KEY (task_id) REFERENCES analysis_tasks(analysis_task_id)";

	// 엔티티가 만드는 스키마에는 없고 마이그레이션에만 있는 것: analysis_dispatches.task_id FK
	// (AnalysisDispatch가 엔티티 연관 없이 UUID만 들고 있어 Hibernate는 이 FK를 만들지 않는다)
	static List<String> withoutDispatchFk(List<String> rows) {
		return rows.stream().filter(r -> !r.equals(DISPATCH_FK)).toList();
	}

	ConfigurableApplicationContext boot(String schema, boolean flywayEnabled, String ddlAuto) {
		SpringApplication app = new SpringApplication(BackendApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		return app.run(
			"--spring.profiles.active=test",
			// 데모 시드가 마이그레이션 검증 결과에 섞이지 않게 한다.
			"--app.demo.enabled=false",
			"--spring.datasource.url=" + url(schema),
			"--spring.datasource.username=" + USER,
			"--spring.datasource.password=" + PASSWORD,
			"--spring.flyway.enabled=" + flywayEnabled,
			"--spring.flyway.baseline-on-migrate=true",
			"--spring.flyway.baseline-version=1",
			"--spring.jpa.hibernate.ddl-auto=" + ddlAuto);
	}

	// ddl-auto: update로 만든 DB를 흉내 낸다: V1과 같은 테이블, 이력 테이블 없음, 오래된 check 제약, 고아 dispatch 행
	static void legacyDatabase(String schema) throws Exception {
		String v1 = StreamUtils.copyToString(new ClassPathResource("db/migration/V1__baseline.sql").getInputStream(), StandardCharsets.UTF_8);
		exec(schema, v1);
		exec(schema, "alter table task_items drop constraint task_items_ticket_status_check");
		exec(schema, """
			alter table task_items add constraint task_items_ticket_status_check
			check (ticket_status in ('ASSIGNEE_REQUIRED', 'PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'FAILED'))""");
		exec(schema, """
			insert into users (login_id, password_hash, name, created_at) values ('u', 'h', 'n', now());
			insert into projects (name, github_owner, github_repo) values ('p', 'o', 'r');
			insert into meetings (project_id, created_by, title, meeting_date, participants_json, original_filename, original_content)
				select p.project_id, u.user_id, 't', current_date, '[]', 'f.txt', 'c' from projects p, users u;
			insert into analysis_tasks (analysis_task_id, meeting_id, status, input_json, created_at)
				select '%s', meeting_id, 'QUEUED', '{}', now() from meetings;
			insert into analysis_dispatches (task_id, state, published, attempts, execution_mode)
				values ('%s', 'QUEUED', false, 0, 'MOCK'), ('%s', 'ACCEPTED', true, 1, 'MOCK')""".formatted(TASK, TASK, ORPHAN));
	}

	@Test
	void migrationsMatchTheSchemaHibernateCreatesFromEntities() throws Exception {
		// 1) 엔티티 기준 Hibernate 스키마 (정답)
		boot(HIBERNATE, false, "create").close();
		// 2) 빈 스키마에 Flyway를 적용한 뒤 ddl-auto: validate로 기동
		boot(FRESH, true, "validate").close();

		assertThat(query(FRESH, "select version || ':' || type || ':' || success from flyway_schema_history order by installed_rank"))
			.containsExactly("1:SQL:true", "2:SQL:true", "3:SQL:true", "4:SQL:true");

		var fresh = describe(FRESH);
		assertThat(fresh).contains(DISPATCH_FK);
		assertThat(withoutDispatchFk(fresh)).containsExactlyElementsOf(describe(HIBERNATE));
	}

	@Test
	void legacyDatabaseCreatedByDdlAutoUpdateConvergesToTheSameSchema() throws Exception {
		boot(FRESH, true, "validate").close(); // 이 테스트의 기준 스키마
		legacyDatabase(LEGACY);

		boot(LEGACY, true, "validate").close();

		assertThat(query(LEGACY, "select version || ':' || type from flyway_schema_history order by installed_rank"))
			.containsExactly("1:BASELINE", "2:SQL", "3:SQL", "4:SQL");
		assertThat(query(LEGACY, "select task_id::text from analysis_dispatches")).containsExactly(TASK);
		// 지운 고아 행은 보관 테이블에 그대로 남는다.
		assertThat(query(LEGACY, "select task_id::text || ':' || state || ':' || attempts from analysis_dispatches_orphans"))
			.containsExactly(ORPHAN + ":ACCEPTED:1");
		assertThat(query(LEGACY, "select convalidated::text from pg_constraint where conname = 'fk_analysis_dispatches_task' and conrelid = 'analysis_dispatches'::regclass"))
			.containsExactly("true");
		assertThat(describe(LEGACY)).containsExactlyElementsOf(describe(FRESH));
	}

	@Test
	void v3AllowsSkippedWithoutChangingExistingTickets() throws Exception {
		legacyDatabase(LEGACY);
		Flyway.configure()
			.dataSource(url(LEGACY), USER, PASSWORD)
			.schemas(LEGACY)
			.locations("classpath:db/migration")
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.target("2")
			.load().migrate();
		exec(LEGACY, """
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status)
			values ('00000000-0000-0000-0000-0000000000a2', '%s', '기존 티켓', '{}', 'CREATE', 'PENDING', 'DRAFT', 'FAILED')""".formatted(TASK));

		// V2에서는 거부되던 값이 V3 적용 후 허용되는지 확인한다.
		assertThatThrownBy(() -> exec(LEGACY, "update task_items set ticket_status = 'SKIPPED'"))
			.isInstanceOf(SQLException.class)
			.hasMessageContaining("task_items_ticket_status_check");
		flyway(LEGACY).migrate();
		assertThat(query(LEGACY, "select title || ':' || ticket_status from task_items"))
			.containsExactly("기존 티켓:FAILED");
		exec(LEGACY, "update task_items set ticket_status = 'SKIPPED'");
		assertThat(query(LEGACY, "select ticket_status from task_items")).containsExactly("SKIPPED");
	}

	@Test
	void legacyRowsWithRetiredEnumValuesAbortV2WithoutChangingData() throws Exception {
		legacyDatabase(LEGACY);
		// 폐기된 enum 값이 남은 기존 DB: 오래된 check를 지우고 지금은 없는 값의 행을 넣는다.
		exec(LEGACY, "alter table task_items drop constraint task_items_ticket_status_check");
		exec(LEGACY, """
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status)
			values ('00000000-0000-0000-0000-0000000000a1', '%s', 't', '{}', 'CREATE', 'PENDING', 'DRAFT', 'RETIRED_VALUE')""".formatted(TASK));

		assertThatThrownBy(() -> flyway(LEGACY).migrate())
			.isInstanceOf(FlywayException.class)
			.hasMessageContaining("task_items.ticket_status")
			.hasMessageContaining("RETIRED_VALUE");

		// V2는 통째로 롤백된다: 이력에 V2 없음, 고아 행과 보관 테이블 변화 없음.
		assertThat(query(LEGACY, "select version from flyway_schema_history where version = '2'")).isEmpty();
		assertThat(query(LEGACY, "select count(*)::text from analysis_dispatches")).containsExactly("2");
		assertThat(query(LEGACY, "select to_regclass('analysis_dispatches_orphans')::text")).containsNull();
		assertThat(query(LEGACY, "select ticket_status from task_items")).containsExactly("RETIRED_VALUE");

		// 값을 정리하면 다음 기동에서 V2와 후속 마이그레이션이 적용된다.
		exec(LEGACY, "update task_items set ticket_status = 'FAILED' where ticket_status = 'RETIRED_VALUE'");
		flyway(LEGACY).migrate();
		assertThat(query(LEGACY, "select version from flyway_schema_history where success order by installed_rank"))
			.containsExactly("1", "2", "3", "4");
	}

	static Flyway flywayTo(String schema, String target) {
		return Flyway.configure()
			.dataSource(url(schema), USER, PASSWORD)
			.schemas(schema)
			.locations("classpath:db/migration")
			.baselineOnMigrate(true)
			.baselineVersion("1")
			.target(target)
			.load();
	}

	static final String ITEM_A = "00000000-0000-0000-0000-0000000000b1";
	static final String ITEM_B = "00000000-0000-0000-0000-0000000000b2";

	@Test
	void v4KeepsExistingTicketsAndBackfillsOneAgentRevisionEach() throws Exception {
		legacyDatabase(LEGACY);
		flywayTo(LEGACY, "3").migrate();
		exec(LEGACY, """
			update analysis_tasks set finished_at = '2026-10-22 10:31:00+09';
			insert into project_members (project_id, user_id, name, github_name)
				select project_id, null, '박종민', 'pjm' from projects;
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status, assignee_member_id, publish_target, processing_type)
				select '%s', '%s', '이미 발행된 티켓', '{"description":"본문","due":{"date":"2026-10-23"},"count":3}', 'CREATE', 'APPROVED', 'SUCCEEDED', 'PUBLISHED', project_member_id, 'NOTION', 'CREATE' from project_members;
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status)
				values ('%s', '%s', '담당 없는 티켓', '{}', 'CREATE', 'PENDING', 'DRAFT', 'ASSIGNEE_REQUIRED')""".formatted(ITEM_A, TASK, ITEM_B, TASK));
		assertThat(query(LEGACY, "select to_regclass('task_item_revisions')::text")).containsNull();

		flyway(LEGACY).migrate();

		// 기존 티켓은 그대로다.
		assertThat(query(LEGACY, "select title || ':' || ticket_status || ':' || content_json from task_items order by title"))
			.containsExactly(
				"담당 없는 티켓:ASSIGNEE_REQUIRED:{}",
				"이미 발행된 티켓:PUBLISHED:{\"description\":\"본문\",\"due\":{\"date\":\"2026-10-23\"},\"count\":3}");
		// 티켓마다 AGENT 리비전 1건. 현재 값 전체 스냅샷이고, 만든 시각은 분석을 마친 시각이다.
		assertThat(query(LEGACY, "select task_item_id::text || ':' || rev || ':' || kind || ':' || schema_version from task_item_revisions order by task_item_id"))
			.containsExactly(ITEM_A + ":1:AGENT:1", ITEM_B + ":1:AGENT:1");
		assertThat(query(LEGACY, "select snapshot->>'title' || '|' || (snapshot->>'assignee_name') || '|' || (snapshot->>'publish_target') || '|' || (snapshot->'content'->'due'->>'date') || '|' || (snapshot->'content'->>'count') from task_item_revisions where task_item_id = '%s'".formatted(ITEM_A)))
			.containsExactly("이미 발행된 티켓|박종민|NOTION|2026-10-23|3");
		assertThat(query(LEGACY, "select (snapshot->'assignee_member_id' = 'null'::jsonb)::text || '|' || (snapshot->'assignee_name' = 'null'::jsonb)::text from task_item_revisions where task_item_id = '%s'".formatted(ITEM_B)))
			.containsExactly("true|true");
		assertThat(query(LEGACY, "select (created_at = '2026-10-22 10:31:00+09')::text || '|' || coalesce(prompt_version, '-') || '|' || coalesce(model, '-') || '|' || coalesce(actor_user_id::text, '-') from task_item_revisions where task_item_id = '%s'".formatted(ITEM_A)))
			.containsExactly("true|-|-|-");
		// 상태 이력은 채우지 않는다.
		assertThat(query(LEGACY, "select count(*)::text from ticket_status_history")).containsExactly("0");
	}

	@Test
	void v4ConstraintsRejectBadKindDuplicateRevAndCascadeOnTicketDelete() throws Exception {
		legacyDatabase(LEGACY);
		flyway(LEGACY).migrate();
		exec(LEGACY, """
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status)
			values ('%s', '%s', 't', '{}', 'CREATE', 'PENDING', 'DRAFT', 'PENDING_REVIEW')""".formatted(ITEM_A, TASK));
		String revision = "insert into task_item_revisions (task_item_id, rev, kind, snapshot, schema_version, created_at) values ('%s', %d, '%s', '{}', 1, now())";

		exec(LEGACY, revision.formatted(ITEM_A, 1, "AGENT"));
		assertThatThrownBy(() -> exec(LEGACY, revision.formatted(ITEM_A, 1, "USER_EDIT")))
			.isInstanceOf(SQLException.class).hasMessageContaining("task_item_revisions_task_item_id_rev_key");
		assertThatThrownBy(() -> exec(LEGACY, revision.formatted(ITEM_A, 2, "DRAFT")))
			.isInstanceOf(SQLException.class).hasMessageContaining("task_item_revisions_kind_check");
		exec(LEGACY, "insert into ticket_status_history (task_item_id, from_status, to_status, created_at) values ('%s', null, 'PENDING_REVIEW', now())".formatted(ITEM_A));
		exec(LEGACY, "insert into ticket_status_history (task_item_id, from_status, to_status, created_at) values ('%s', 'FAILED', 'SKIPPED', now())".formatted(ITEM_A));
		assertThatThrownBy(() -> exec(LEGACY, "insert into ticket_status_history (task_item_id, from_status, to_status, created_at) values ('%s', 'CONFIRMED', 'DONE', now())".formatted(ITEM_A)))
			.isInstanceOf(SQLException.class).hasMessageContaining("ticket_status_history_to_status_check");

		// 프로젝트 삭제는 task_items를 일괄 삭제하므로 이력은 DB가 함께 지운다.
		exec(LEGACY, "delete from task_items where task_item_id = '%s'".formatted(ITEM_A));
		assertThat(query(LEGACY, "select (select count(*) from task_item_revisions) || ':' || (select count(*) from ticket_status_history)"))
			.containsExactly("0:0");
	}

	@Test
	void v4AbortsWithoutChangingDataWhenContentJsonIsNotJson() throws Exception {
		legacyDatabase(LEGACY);
		flywayTo(LEGACY, "3").migrate();
		exec(LEGACY, """
			insert into task_items (task_item_id, task_id, title, content_json, action, review_status, publish_status, ticket_status)
			values ('%s', '%s', '정상', '{}', 'CREATE', 'PENDING', 'DRAFT', 'FAILED'),
			       ('%s', '%s', '깨진 티켓', 'not json', 'CREATE', 'PENDING', 'DRAFT', 'FAILED')""".formatted(ITEM_A, TASK, ITEM_B, TASK));

		assertThatThrownBy(() -> flyway(LEGACY).migrate())
			.isInstanceOf(FlywayException.class)
			.hasMessageContaining(ITEM_B)
			.hasMessageContaining("V4 중단");

		// V4는 통째로 롤백된다: 이력에 V4 없음, 새 테이블 없음, 티켓 그대로.
		assertThat(query(LEGACY, "select version from flyway_schema_history where version = '4'")).isEmpty();
		assertThat(query(LEGACY, "select to_regclass('task_item_revisions')::text || '|' || to_regclass('ticket_status_history')::text"))
			.containsNull();
		assertThat(query(LEGACY, "select count(*)::text from task_items")).containsExactly("2");

		// 정리하면 다음 기동에서 적용된다.
		exec(LEGACY, "update task_items set content_json = '{}' where task_item_id = '%s'".formatted(ITEM_B));
		flyway(LEGACY).migrate();
		assertThat(query(LEGACY, "select count(*)::text from task_item_revisions")).containsExactly("2");
	}
}
