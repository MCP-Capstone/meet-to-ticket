package com.mcpcapstone.meettoticket.agent;

import lombok.*;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {
    private String mode = "live";
    private String baseUrl = "http://localhost:8001";
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
    private Duration taskTimeout = Duration.ofMinutes(10);
    private String mockScenario = "draft";
}
