package com.mcpcapstone.meettoticket.common.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * demo/test가 아닌 프로파일에서 기본 자격증명이나 비어 있는 내부 인증 토큰으로 기동하면 부팅을 거부한다.
 * demo 프로파일은 한 번에 기동되도록 이 검사를 건너뛴다.
 */
@Component
@Profile("!demo & !test")
public class StartupSecurityGuard {

	static final String DEFAULT_CREDENTIAL = "meettoticket";

	public StartupSecurityGuard(
			@Value("${BACKEND_INTERNAL_TOKEN:}") String internalToken,
			@Value("${spring.datasource.password:}") String dbPassword,
			@Value("${spring.rabbitmq.password:}") String rabbitPassword) {
		List<String> problems = check(internalToken, dbPassword, rabbitPassword);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("운영 프로파일 기동 거부: " + String.join("; ", problems)
					+ ". 값을 설정하거나 데모 실행이면 SPRING_PROFILES_ACTIVE=demo를 사용하세요.");
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
}
