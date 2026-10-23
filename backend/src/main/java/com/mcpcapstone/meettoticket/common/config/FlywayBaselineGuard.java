package com.mcpcapstone.meettoticket.common.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/**
 * baseline-on-migrate는 이력 테이블이 없는 비어 있지 않은 DB를 통째로 V1으로 간주한다.
 * 핵심 테이블이 일부만 있는 DB(중간에 실패한 초기화 등)를 V1로 잘못 기록하지 않도록 마이그레이션 전에 막는다.
 */
@Component
public class FlywayBaselineGuard implements FlywayMigrationStrategy {
	// V1이 만드는 테이블. V1에 테이블을 추가해도 이 목록은 바꾸지 않는다(V1은 수정하지 않는다).
	static final List<String> V1_TABLES = List.of(
		"users", "projects", "project_members", "meetings", "analysis_tasks",
		"task_items", "publish_batches", "project_reindexes", "analysis_dispatches");

	@Override
	public void migrate(Flyway flyway) {
		verify(flyway.getConfiguration().getDataSource());
		flyway.migrate();
	}

	static void verify(DataSource dataSource) {
		Set<String> existing = new HashSet<>();
		try (Connection c = dataSource.getConnection();
			var rs = c.getMetaData().getTables(null, c.getSchema(), "%", new String[] {"TABLE"})) {
			while (rs.next()) existing.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
		} catch (SQLException e) {
			throw new IllegalStateException("스키마 상태를 확인하지 못했습니다.", e);
		}
		if (existing.contains("flyway_schema_history") || existing.isEmpty()) return;
		List<String> missing = V1_TABLES.stream().filter(t -> !existing.contains(t)).toList();
		if (!missing.isEmpty())
			throw new IllegalStateException("Flyway 이력이 없는 DB에 테이블이 있지만 V1 테이블이 모두 있지 않아 baseline을 적용하지 않습니다. 없는 테이블: "
				+ missing + ". 로컬 DB라면 비우고 다시 기동하세요. (scripts/db/README.md)");
	}
}
