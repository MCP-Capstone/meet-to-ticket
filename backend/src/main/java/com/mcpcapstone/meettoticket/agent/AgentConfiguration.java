package com.mcpcapstone.meettoticket.agent;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {
    @Bean("agentTransport")
    @ConditionalOnProperty(name = "agent.mode", havingValue = "live", matchIfMissing = true)
    public AgentGateway liveAgentGateway(AgentProperties properties) {
        return new LiveAgentGateway(agentClient(properties));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mode", havingValue = "live", matchIfMissing = true)
    public RepositoryPermissionGateway liveRepositoryPermissionGateway(AgentProperties properties) {
        return new LiveRepositoryPermissionGateway(agentClient(properties));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mode", havingValue = "live", matchIfMissing = true)
    public ProjectConnectionGateway liveProjectConnectionGateway(AgentProperties properties) {
		return new LiveProjectConnectionGateway(agentClient(properties, properties.getConnectionReadTimeout()));
    }

    @Bean
    @ConditionalOnProperty(name = "agent.mode", havingValue = "live", matchIfMissing = true)
    public PublishGateway livePublishGateway(AgentProperties properties) {
        return new LivePublishGateway(agentClient(properties));
    }

    private static RestClient agentClient(AgentProperties properties) {
		return agentClient(properties, properties.getReadTimeout());
	}

	private static RestClient agentClient(AgentProperties properties, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeout());
		factory.setReadTimeout(readTimeout);
        RestClient.Builder builder =
                RestClient.builder().baseUrl(properties.getBaseUrl()).requestFactory(factory);
        // Agent(#52)는 아직 내부 키를 검사하지 않는다. 키를 설정한 경우에만 헤더를 붙인다.
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank())
            builder.defaultHeader("X-Internal-Api-Key", properties.getApiKey());
        return builder.build();
    }
}
