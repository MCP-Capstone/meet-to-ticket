package com.mcpcapstone.meettoticket.agent;

// 메시지는 고정된 검증 조건만 포함한다. Agent 응답·원문·인증키를 넣지 않는다.
public class AgentResultValidationException extends IllegalArgumentException {
    public AgentResultValidationException(String condition) {
        super("Agent 응답 검증 실패: " + condition);
    }
}
