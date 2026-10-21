package com.mcpcapstone.meettoticket;

import com.mcpcapstone.meettoticket.mock.MockDataInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
	"spring.datasource.url=${TEST_DB_URL}", "spring.datasource.username=${TEST_DB_USERNAME}",
	"spring.datasource.password=${TEST_DB_PASSWORD:}", "spring.jpa.hibernate.ddl-auto=create-drop"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MockProfileIsolationTest {
	@Autowired ApplicationContext context;
	@Autowired JdbcTemplate jdbc;

	@Test
	void normalProfileDoesNotSeed() {
		assertThat(context.getBeansOfType(MockDataInitializer.class)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from meetings", Long.class)).isZero();
	}
}
