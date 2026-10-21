package com.mcpcapstone.meettoticket.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
    @Bean
    @ConditionalOnProperty(name = "agent.mode", havingValue = "live", matchIfMissing = true)
    public AgentGateway liveAgentGateway(AgentProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
        factory.setReadTimeout(properties.getReadTimeout());
        return new LiveAgentGateway(
                RestClient.builder()
                        .baseUrl(properties.getBaseUrl())
                        .requestFactory(factory)
                        .defaultHeader("X-Internal-Api-Key", properties.getApiKey())
                        .build());
    }
}
