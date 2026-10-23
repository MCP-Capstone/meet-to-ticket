package com.mcpcapstone.meettoticket.analysis;

// 발행 때 어떻게 처리할지. 티켓 상태와는 별도다 (신규 발행, 기존 이슈 연결, 기존 이슈 갱신).
public enum ProcessingType {
    CREATE,
    LINK,
    UPDATE;

    // Agent action(CREATE, UPDATE, DUPLICATE, NEEDS_CHECK)을 옮긴다. 판단이 필요한 NEEDS_CHECK는 신규 발행으로 둔다.
    public static ProcessingType fromAgentAction(String action) {
        return switch (action == null ? "" : action) {
            case "UPDATE" -> UPDATE;
            case "DUPLICATE" -> LINK;
            default -> CREATE;
        };
    }
}
