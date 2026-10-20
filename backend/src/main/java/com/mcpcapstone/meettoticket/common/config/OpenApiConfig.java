package com.mcpcapstone.meettoticket.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

// Swagger UI: http://localhost:8080/swagger-ui.html
@Configuration
public class OpenApiConfig {

	public static final String SESSION_COOKIE = "sessionCookie";

	@Bean
	public OpenAPI openApi() {
		return new OpenAPI()
				.info(new Info()
						.title("meet-to-ticket API")
						.version("v1")
						.description("""
								회의록에서 할 일을 추출해 GitHub 이슈로 발행하는 서비스의 Frontend ↔ Backend API.

								- 로그인은 서버 세션(JSESSIONID 쿠키)으로 유지한다. JWT는 쓰지 않는다.
								- `/api/auth/**`, `/api/health`를 뺀 API는 로그인이 필요하다. 없으면 401 `UNAUTHORIZED`.
								- Swagger에서 테스트할 때는 먼저 `/api/auth/login`을 실행하면 이후 요청에 쿠키가 자동으로 붙는다.
								- 에러 응답은 모두 `{ "code", "message" }` 형식이다.
								"""))
				.components(new Components().addSecuritySchemes(SESSION_COOKIE, new SecurityScheme()
						.type(SecurityScheme.Type.APIKEY)
						.in(SecurityScheme.In.COOKIE)
						.name("JSESSIONID")
						.description("로그인하면 발급되는 세션 쿠키")))
				// 기본은 로그인 필요. 로그인이 필요 없는 API는 @SecurityRequirements로 비운다.
				.addSecurityItem(new SecurityRequirement().addList(SESSION_COOKIE));
	}
}
