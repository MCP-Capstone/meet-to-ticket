package com.mcpcapstone.meettoticket.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * demo/test/local 외의 프로파일(프로파일 미지정 포함)에서 기본 자격증명이나 비어 있는 내부 인증 토큰이면
 * 부팅을 거부한다. EnvironmentPostProcessor라서 어떤 빈도 만들어지기 전(DB/브로커 연결, 스키마 변경 전)에 실행된다.
 * 설정 파일 로딩(ConfigDataEnvironmentPostProcessor) 뒤에 실행되도록 가장 늦은 순서를 쓴다.
 */
public class StartupSecurityGuard implements EnvironmentPostProcessor, Ordered {

	static final String DEFAULT_CREDENTIAL = "meettoticket";
	static final Set<String> RELAXED_PROFILES = Set.of("demo", "test", "local");

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		for (String profile : environment.getActiveProfiles()) {
			if (RELAXED_PROFILES.contains(profile))
				return;
		}
		List<String> problems = check(
				environment.getProperty("BACKEND_INTERNAL_TOKEN"),
				environment.getProperty("spring.datasource.password"),
				environment.getProperty("spring.rabbitmq.password"));
		if (!problems.isEmpty()) {
			throw new IllegalStateException("운영 프로파일 기동 거부: " + String.join("; ", problems)
					+ ". 값을 설정하거나 로컬/데모 실행이면 SPRING_PROFILES_ACTIVE=local 또는 demo를 사용하세요.");
		}
	}

	static List<String> check(String internalToken, String dbPassword, String rabbitPassword) {
		List<String> problems = new ArrayList<>();
		if (isBlank(internalToken))
			problems.add("BACKEND_INTERNAL_TOKEN이 비어 있음");
		if (isBlank(dbPassword) || DEFAULT_CREDENTIAL.equals(dbPassword))
			problems.add("DB_PASSWORD가 비어 있거나 기본값임");
		if (isBlank(rabbitPassword) || DEFAULT_CREDENTIAL.equals(rabbitPassword))
			problems.add("RABBITMQ_PASSWORD가 비어 있거나 기본값임");
		return problems;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
