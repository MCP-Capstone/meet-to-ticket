package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(AgentConfiguration.class);

    // Agent(#52)는 내부 키를 검사하지 않아 LIVE도 키 없이 기동한다.
    @Test
    void LIVE는_내부키_없이도_게이트웨이를_생성한다() {
        context.run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(AgentGateway.class);
        });
    }

    @Test
    void LIVE에서_키를_설정하면_게이트웨이를_생성한다() {
        context.withPropertyValues("agent.api-key=test-key").run(c -> {
            assertThat(c).hasNotFailed();
            assertThat(c).hasSingleBean(AgentGateway.class);
        });
    }

    @Test
    void MOCK는_키_없이_기동할_수_있다() {
        context.withPropertyValues("agent.mode=mock").run(c -> assertThat(c).hasNotFailed());
    }
}
