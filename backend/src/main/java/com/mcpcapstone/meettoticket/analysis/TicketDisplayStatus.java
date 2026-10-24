package com.mcpcapstone.meettoticket.analysis;

import io.swagger.v3.oas.annotations.media.Schema;

// 사용자에게 보이는 4상태 (#264). 저장하지 않고 ticket_status(와 발행 오류 코드)에서 매번 파생한다.
// ticket_status가 유일한 원천이고, 이 값은 화면 요약(필터, 배지, 건수)용이다. 세부 동작은 ticket_status로 판단한다.
@Schema(description = "사용자 노출 상태. REVIEW_PENDING 검토 대기, PUBLISHING 발행 중, PUBLISHED 발행됨, NEEDS_ATTENTION 실패/확인 필요")
public enum TicketDisplayStatus {
    REVIEW_PENDING, // 검토 대기
    PUBLISHING, // 발행 중
    PUBLISHED, // 발행됨
    NEEDS_ATTENTION; // 실패(확인 필요)

    // 매핑 (docs/revisions-and-history.md "사용자 노출 상태"):
    //  ASSIGNEE_REQUIRED, PENDING_REVIEW, CONFIRMED -> 검토 대기
    //      외부 도구에 아직 아무것도 보내지 않았고 사람이 할 일(담당 지정, 검토, 발행 버튼)이 남았다.
    //      CONFIRMED를 발행 중으로 보면 실제로는 진행 중이 아닌 티켓이 진행 중으로 보인다.
    //  PUBLISHING -> 발행 중
    //  PUBLISHED, LINKED, UPDATED -> 발행됨 (UPDATED이면서 알림 댓글 실패 등 오류 코드가 남았으면 확인 필요)
    //  FAILED, SKIPPED -> 실패(확인 필요)
    //      SKIPPED(발행 생략)는 외부에 아무것도 만들지 않았으므로 발행됨에 합치지 않고, 발행되지 않았다는 사실을 알려야 해서 여기에 둔다.
    //  REJECTED -> null. 담당자가 끝낸 결정이라 4상태 어디에도 속하지 않는다 (되돌리기 전까지 목록에서 따로 본다).
    // publishErrorCode는 TaskItem.getPublishErrorCode()다. UPDATED가 아니면 보지 않는다.
    public static TicketDisplayStatus of(TicketStatus status, String publishErrorCode) {
        return switch (status) {
            case ASSIGNEE_REQUIRED, PENDING_REVIEW, CONFIRMED -> REVIEW_PENDING;
            case PUBLISHING -> PUBLISHING;
            case PUBLISHED, LINKED -> PUBLISHED;
            case UPDATED -> publishErrorCode == null ? PUBLISHED : NEEDS_ATTENTION;
            case SKIPPED, FAILED -> NEEDS_ATTENTION;
            case REJECTED -> null;
        };
    }
}
