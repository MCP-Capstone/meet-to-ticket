package com.mcpcapstone.meettoticket.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// MOCK 모드에서는 실제 외부 발행이나 자동 결과 콜백을 만들지 않는다. 테스트가 결과 콜백을 명시적으로 보낸다.
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockPublishGateway implements PublishGateway {
    @Override
    public void submit(AgentContracts.PublishBatchRequest request) {}
}
