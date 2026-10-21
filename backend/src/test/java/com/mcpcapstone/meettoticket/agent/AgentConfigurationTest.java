package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AgentConfigurationTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(AgentConfiguration.class);

    @Test
    void LIVE에서_누락되거나_공백인_내부키로_기동하지_않는다() {
        context.run(c -> assertThat(c.getStartupFailure())
                .hasRootCauseMessage("LIVE 모드에는 AGENT_INTERNAL_API_KEY 설정이 필요합니다."));
        context.withPropertyValues("agent.api-key=   ").run(c ->
                assertThat(c.getStartupFailure())
                        .hasRootCauseMessage("LIVE 모드에는 AGENT_INTERNAL_API_KEY 설정이 필요합니다."));
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
