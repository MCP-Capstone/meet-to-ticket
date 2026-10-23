package com.mcpcapstone.meettoticket.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 실제 SpringApplication 기동 경로(application.yml 로딩 포함)로 가드를 검증한다.
class StartupSecurityGuardTest {

	static final AtomicBoolean beanCreated = new AtomicBoolean();

	@Configuration
	static class ProbeConfig {
		@Bean
		Object probe() {
			beanCreated.set(true);
			return new Object();
		}
	}

	@BeforeEach
	void reset() {
		beanCreated.set(false);
	}

	private ConfigurableApplicationContext run(String profile, String... props) {
		SpringApplicationBuilder builder = new SpringApplicationBuilder(ProbeConfig.class)
				.web(WebApplicationType.NONE);
		if (profile != null)
			builder.profiles(profile);
		// 명령행 인자는 application.yml보다 우선한다(properties()는 기본값이라 yml에 밀림)
		String[] args = java.util.Arrays.stream(props).map(p -> "--" + p).toArray(String[]::new);
		return builder.run(args);
	}

	private static final String[] SAFE = {
			"BACKEND_INTERNAL_TOKEN=tok-1", "spring.datasource.password=db-pw", "spring.rabbitmq.password=mq-pw" };
	private static final String[] DEFAULTS = {
			"BACKEND_INTERNAL_TOKEN=", "spring.datasource.password=meettoticket", "spring.rabbitmq.password=meettoticket" };

	@Test
	void 프로파일_미지정에서_기본_자격증명이면_빈_생성_전에_기동_실패한다() {
		assertThatThrownBy(() -> run(null, DEFAULTS))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("BACKEND_INTERNAL_TOKEN")
				.hasMessageContaining("DB_PASSWORD").hasMessageContaining("RABBITMQ_PASSWORD");
		assertThat(beanCreated).isFalse();
	}

	@Test
	void 프로파일_미지정에서_안전한_값이면_기동한다() {
		try (ConfigurableApplicationContext ctx = run(null, SAFE)) {
			assertThat(beanCreated).isTrue();
		}
	}

	@Test
	void 임의_프로파일_예_prod에서도_기본값이면_거부한다() {
		assertThatThrownBy(() -> run("prod", DEFAULTS)).isInstanceOf(IllegalStateException.class);
		assertThat(beanCreated).isFalse();
	}

	@Test
	void demo_test_local_프로파일은_검사를_건너뛴다() {
		for (String profile : new String[] { "demo", "test", "local" }) {
			beanCreated.set(false);
			try (ConfigurableApplicationContext ctx = run(profile, DEFAULTS)) {
				assertThat(beanCreated).as(profile).isTrue();
			}
		}
	}

	@Test
	void 공백_토큰도_거부한다() {
		assertThat(StartupSecurityGuard.check("  ", "db-pw", "mq-pw")).hasSize(1);
	}
}
