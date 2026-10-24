package com.mcpcapstone.meettoticket.analysis;

// 같은 티켓이 같은 대상에 하는 서로 다른 외부 쓰기. 멱등키의 일부라서 작업마다 따로 한 번씩만 반영된다 (#263).
public enum PublishOperation {
    CREATE,
    LINK,
    UPDATE,
    COMMENT; // 갱신 뒤 알림 댓글만 다시 보내는 작업. 본문 갱신(UPDATE)과 별개로 한 번만 반영돼야 한다

    // Agent에 보내는 처리 유형(CREATE, LINK, UPDATE)과 댓글 재전송 여부로 정한다.
    public static PublishOperation of(String processingType, boolean commentOnly) {
        if (commentOnly) return COMMENT;
        return switch (processingType == null ? "" : processingType) {
            case "LINK" -> LINK;
            case "UPDATE" -> UPDATE;
            default -> CREATE;
        };
    }
}
