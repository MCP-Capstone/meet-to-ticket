package com.mcpcapstone.meettoticket.analysis;

import java.util.EnumSet;
import java.util.Set;

// 외부 발행 한 건(publications 행)의 상태 (#263). 전이 표는 docs/publications.md와 같다.
// 전이는 모두 "현재 상태가 이 중 하나일 때만" 거는 조건부 UPDATE(CAS)로 하므로, 동시 요청 중 한쪽만 전이에 성공한다.
public enum PublicationStatus {
    RESERVED, // 외부에 쓰기 전에 남긴 예약. 이 상태를 만든 쪽만 Agent에 보낸다
    SENT, // Agent가 접수했다(또는 접수 여부를 모른 채 보냈다). 결과를 기다리는 중
    CONFIRMED, // 외부 반영이 확정됐다. 같은 키에서 종결 상태
    UNCERTAIN, // 요청은 갔지만 반영 여부를 모른다. 확인 전에는 다시 보내지 않는다
    FAILED; // 반영되지 않은 것이 확실하다. 같은 키로 새 시도를 시작할 수 있다

    // 이 상태에서 갈 수 있는 상태. UNCERTAIN -> RESERVED는 재대조할 수 있는 대상(PublishTarget.recheckable)에서만 쓴다.
    // RESERVED에서 결과 상태로 바로 가는 것은 SENT 표시보다 Agent 보고가 먼저 도착한 경우다.
    public Set<PublicationStatus> next() {
        return switch (this) {
            case RESERVED -> EnumSet.of(SENT, CONFIRMED, UNCERTAIN, FAILED);
            case SENT -> EnumSet.of(CONFIRMED, UNCERTAIN, FAILED);
            case UNCERTAIN -> EnumSet.of(RESERVED, CONFIRMED, FAILED);
            case FAILED -> EnumSet.of(RESERVED);
            case CONFIRMED -> EnumSet.noneOf(PublicationStatus.class);
        };
    }

    public boolean canMoveTo(PublicationStatus to) {
        return next().contains(to);
    }

    // 결과를 기다리는 상태. 크래시 복구(PR2)가 멈춘 행을 찾을 때의 기준이기도 하다.
    public boolean inFlight() {
        return this == RESERVED || this == SENT;
    }
}
