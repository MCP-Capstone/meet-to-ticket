package com.mcpcapstone.meettoticket.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcpcapstone.meettoticket.BackendApplication;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * Flyway 마이그레이션이 엔티티가 만드는 스키마와 같은지 실제 PostgreSQL에서 검증한다.
 * 마이그레이션은 PostgreSQL 전용 SQL이라 H2 테스트에서는 돌릴 수 없다. 접속 정보가 있을 때만 실행한다.
 *
 * <pre>
 * MIGRATION_TEST_DB_URL=jdbc:postgresql://localhost:5432/postgres ./gradlew test --tests '*MigrationSchemaTest'
 * </pre>
 *
 * 사용자 / 비밀번호는 MIGRATION_TEST_DB_USERNAME / MIGRATION_TEST_DB_PASSWORD(기본 meettoticket)다.
 * 테스트용 스키마(mig_*)를 만들고 지우므로 개발 데이터가 든 DB를 가리키지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_DB_URL", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationSchemaTest {
	static final String BASE_URL = System.getenv("MIGRATION_TEST_DB_URL");
	static final String USER = envOr("MIGRATION_TEST_DB_USERNAME", "meettoticket");
	static final String PASSWORD = envOr("MIGRATION_TEST_DB_PASSWORD", "meettoticket");
	static final String FRESH = "mig_fresh";
	static final String HIBERNATE = "mig_hibernate";
	static final String LEGACY = "mig_legacy";

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

	static void recreate(String schema) throws SQLException {
		exec("public", "drop schema if exists " + schema + " cascade");
		exec("public", "create schema " + schema);
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

	@BeforeAll
	static void clean() throws SQLException {
		for (String schema : List.of(FRESH, HIBERNATE, LEGACY)) recreate(schema);
	}

	@AfterAll
	static void drop() throws SQLException {
		for (String schema : List.of(FRESH, HIBERNATE, LEGACY)) exec("public", "drop schema if exists " + schema + " cascade");
	}

	// 컬럼 타입 / NULL / 기본값 / identity, 제약, 인덱스를 이름 없이 비교할 수 있는 문자열로 만든다.
	static List<String> describe(String schema) throws SQLException {
		List<String> rows = new ArrayList<>(query(schema, """
			select 'col ' || c.relname || '.' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod)
				|| ' notnull=' || a.attnotnull || ' identity=' || a.attidentity::text
				|| ' default=' || coalesce(pg_get_expr(d.adbin, d.adrelid), '')
			from pg_attribute a
			join pg_class c on c.oid = a.attrelid and c.relkind = 'r'
			join pg_namespace n on n.oid = c.relnamespace and n.nspname = '%s'
			left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
			where a.attnum > 0 and not a.attisdropped and c.relname <> 'flyway_schema_history'
			""".formatted(schema)));
		rows.addAll(query(schema, """
			select 'con ' || c.relname || ' ' || con.contype::text || ' ' || pg_get_constraintdef(con.oid)
			from pg_constraint con
			join pg_class c on c.oid = con.conrelid
			join pg_namespace n on n.oid = c.relnamespace and n.nspname = '%s'
			where c.relname <> 'flyway_schema_history'
			""".formatted(schema)));
		rows.addAll(query(schema, """
			select 'idx ' || regexp_replace(indexdef, 'INDEX \\S+ ON ' || schemaname || '\\.', 'INDEX ON ')
			from pg_indexes where schemaname = '%s' and tablename <> 'flyway_schema_history'
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
			"--spring.datasource.url=" + url(schema),
			"--spring.datasource.username=" + USER,
			"--spring.datasource.password=" + PASSWORD,
			"--spring.flyway.enabled=" + flywayEnabled,
			"--spring.flyway.baseline-on-migrate=true",
			"--spring.flyway.baseline-version=1",
			"--spring.jpa.hibernate.ddl-auto=" + ddlAuto);
	}

	@Test
	@Order(1)
	void migrationsMatchTheSchemaHibernateCreatesFromEntities() throws Exception {
		// 1) 엔티티 기준 Hibernate 스키마 (정답)
		boot(HIBERNATE, false, "create").close();
		// 2) 빈 스키마에 Flyway를 적용한 뒤 ddl-auto: validate로 기동
		boot(FRESH, true, "validate").close();

		assertThat(query(FRESH, "select version || ':' || type || ':' || success from flyway_schema_history order by installed_rank"))
			.containsExactly("1:SQL:true", "2:SQL:true");

		var fresh = describe(FRESH);
		assertThat(fresh).contains(DISPATCH_FK);
		assertThat(withoutDispatchFk(fresh)).containsExactlyElementsOf(describe(HIBERNATE));
	}

	@Test
	@Order(2)
	void legacyDatabaseCreatedByDdlAutoUpdateConvergesToTheSameSchema() throws Exception {
		// ddl-auto: update로 만든 DB를 흉내 낸다: V1과 같은 테이블에 이력 테이블이 없고, 오래된 check 제약과 고아 dispatch 행이 있다.
		String v1 = StreamUtils.copyToString(new ClassPathResource("db/migration/V1__baseline.sql").getInputStream(), StandardCharsets.UTF_8);
		exec(LEGACY, v1);
		exec(LEGACY, "alter table task_items drop constraint task_items_ticket_status_check");
		exec(LEGACY, """
			alter table task_items add constraint task_items_ticket_status_check
			check (ticket_status in ('ASSIGNEE_REQUIRED', 'PENDING_REVIEW', 'CONFIRMED', 'REJECTED', 'FAILED'))""");
		exec(LEGACY, """
			insert into users (login_id, password_hash, name, created_at) values ('u', 'h', 'n', now());
			insert into projects (name, github_owner, github_repo) values ('p', 'o', 'r');
			insert into meetings (project_id, created_by, title, meeting_date, participants_json, original_filename, original_content)
				select p.project_id, u.user_id, 't', current_date, '[]', 'f.txt', 'c' from projects p, users u;
			insert into analysis_tasks (analysis_task_id, meeting_id, status, input_json, created_at)
				select '00000000-0000-0000-0000-000000000001', meeting_id, 'QUEUED', '{}', now() from meetings;
			insert into analysis_dispatches (task_id, state, published, attempts, execution_mode)
				values ('00000000-0000-0000-0000-000000000001', 'QUEUED', false, 0, 'MOCK'),
					('00000000-0000-0000-0000-0000000000ff', 'ACCEPTED', true, 1, 'MOCK')""");

		boot(LEGACY, true, "validate").close();

		assertThat(query(LEGACY, "select version || ':' || type from flyway_schema_history order by installed_rank"))
			.containsExactly("1:BASELINE", "2:SQL");
		assertThat(query(LEGACY, "select task_id::text from analysis_dispatches"))
			.containsExactly("00000000-0000-0000-0000-000000000001");
		assertThat(query(LEGACY, "select convalidated::text from pg_constraint where conname = 'fk_analysis_dispatches_task' and conrelid = 'analysis_dispatches'::regclass"))
			.containsExactly("true");
		assertThat(describe(LEGACY)).isEqualTo(describe(FRESH));
	}
}
