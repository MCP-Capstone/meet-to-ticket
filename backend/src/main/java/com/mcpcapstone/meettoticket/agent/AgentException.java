package com.mcpcapstone.meettoticket.agent;

public class AgentException extends RuntimeException {
    private final boolean uncertain;

    public AgentException(boolean uncertain) {
        super(uncertain ? "Agent 응답을 확인하지 못했습니다. 같은 작업을 조회하며 확인합니다." : "Agent가 요청을 처리하지 못했습니다.");
        this.uncertain = uncertain;
    }

    public boolean uncertain() {
        return uncertain;
    }
}
