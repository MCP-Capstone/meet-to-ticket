package com.mcpcapstone.meettoticket.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StartupSecurityGuardTest {

	@Test
	void 안전한_값이면_통과한다() {
		assertThatCode(() -> new StartupSecurityGuard("tok-1", "db-pw", "mq-pw")).doesNotThrowAnyException();
	}

	@Test
	void 빈_내부_토큰이면_거부한다() {
		assertThatThrownBy(() -> new StartupSecurityGuard("  ", "db-pw", "mq-pw"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("BACKEND_INTERNAL_TOKEN");
	}

	@Test
	void 기본_DB_비밀번호면_거부한다() {
		assertThatThrownBy(() -> new StartupSecurityGuard("tok-1", "meettoticket", "mq-pw"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DB_PASSWORD");
	}

	@Test
	void 기본_브로커_비밀번호면_거부한다() {
		assertThatThrownBy(() -> new StartupSecurityGuard("tok-1", "db-pw", "meettoticket"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("RABBITMQ_PASSWORD");
	}

	@Test
	void 모든_문제를_함께_보고한다() {
		assertThat(StartupSecurityGuard.check(null, "", "meettoticket")).hasSize(3);
	}
}
