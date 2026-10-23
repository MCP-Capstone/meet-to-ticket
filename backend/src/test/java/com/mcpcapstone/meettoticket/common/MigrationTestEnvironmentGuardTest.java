package com.mcpcapstone.meettoticket.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** CI에서 MIGRATION_TEST_DB_URL이 빠지면 MigrationSchemaTest가 조용히 skip되므로, 그 경우 실패시킨다. */
class MigrationTestEnvironmentGuardTest {
	@Test
	void ciMustProvideMigrationTestDatabase() {
		boolean ci = isSet("CI") || isSet("GITHUB_ACTIONS");
		if (!ci) return; // 로컬에서는 PostgreSQL 없이도 빌드할 수 있다.
		assertThat(System.getenv("MIGRATION_TEST_DB_URL"))
			.as("CI에서는 MigrationSchemaTest를 건너뛸 수 없다. MIGRATION_TEST_DB_URL을 설정하세요.")
			.isNotBlank();
	}

	private static boolean isSet(String name) {
		String value = System.getenv(name);
		return value != null && !value.isBlank();
	}
}
