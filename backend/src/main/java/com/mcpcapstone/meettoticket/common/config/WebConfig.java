package com.mcpcapstone.meettoticket.common.config;

import com.mcpcapstone.meettoticket.auth.LoginCheckInterceptor;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // /api/**는 세션으로 보호한다. Agent는 Spring이 서버 간 HTTP로 조회한다.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginCheckInterceptor())
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/**", "/api/health");
    }
}
