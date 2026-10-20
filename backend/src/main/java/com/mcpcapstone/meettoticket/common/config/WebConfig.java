package com.mcpcapstone.meettoticket.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.mcpcapstone.meettoticket.auth.LoginCheckInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

	// Agent 콜백은 /internal/** 로 따로 두어 이 검사와 Vite 프록시 대상에서 모두 빠진다.
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new LoginCheckInterceptor())
				.addPathPatterns("/api/**")
				.excludePathPatterns("/api/auth/**", "/api/health");
	}
}
