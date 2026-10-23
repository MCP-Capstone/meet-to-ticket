package com.mcpcapstone.meettoticket.agent;

// 확인된 카드 묶음을 Agent /publish로 한 번만 접수한다. 결과는 내부 콜백으로 따로 받는다.
public interface PublishGateway {
    void submit(AgentContracts.PublishBatchRequest request);
}
