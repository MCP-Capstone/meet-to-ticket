package com.mcpcapstone.meettoticket.analysis;

// 기획 4.2 티켓 상태. 전이는 TASK-028/029에서 다룬다.
public enum TicketStatus {
    ASSIGNEE_REQUIRED, // 담당 지정 필요
    PENDING_REVIEW, // 검토 대기
    CONFIRMED, // 확인
    PUBLISHING, // 발행 중 (일괄 발행 결과를 기다리는 중. 중복 클릭을 막는다)
    REJECTED, // 거절
    PUBLISHED, // 발행됨
    LINKED, // 연결됨
    UPDATED, // 갱신됨
    FAILED // 실패
}
