package com.mcpcapstone.meettoticket.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;

class FlywayBaselineGuardTest {
	private DriverManagerDataSource database(String name) {
		var ds = new DriverManagerDataSource("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
		return ds;
	}

	private void create(DriverManagerDataSource ds, String... tables) throws SQLException {
		try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
			for (String t : tables) s.execute("create table " + t + " (id int)");
		}
	}

	@Test
	void emptyDatabaseIsAllowed() {
		assertThatCode(() -> FlywayBaselineGuard.verify(database("guardEmpty"))).doesNotThrowAnyException();
	}

	@Test
	void databaseWithAllV1TablesIsAllowedForBaseline() throws Exception {
		var ds = database("guardFull");
		create(ds, FlywayBaselineGuard.V1_TABLES.toArray(String[]::new));
		assertThatCode(() -> FlywayBaselineGuard.verify(ds)).doesNotThrowAnyException();
	}

	@Test
	void partialDatabaseWithoutHistoryIsRejected() throws Exception {
		var ds = database("guardPartial");
		create(ds, "users", "projects");
		assertThatThrownBy(() -> FlywayBaselineGuard.verify(ds))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("meetings");
	}

	@Test
	void partialDatabaseWithHistoryIsLeftToFlyway() throws Exception {
		var ds = database("guardHistory");
		create(ds, "users", "flyway_schema_history");
		assertThat(FlywayBaselineGuard.V1_TABLES).contains("users");
		assertThatCode(() -> FlywayBaselineGuard.verify(ds)).doesNotThrowAnyException();
	}
}
