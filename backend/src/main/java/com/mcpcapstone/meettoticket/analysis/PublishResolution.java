package com.mcpcapstone.meettoticket.analysis;

// 발행 실패를 푸는 방법 (#280). 화면은 대상 이름 대신 이 값으로 다시 시도 버튼과 확인 결과 버튼 중 하나를 보여 준다.
public enum PublishResolution {
    RETRY, // 다시 시도. 결과 모름이면 Agent가 발행 직전 재대조한다
    MANUAL_CHECK; // 재대조할 수 없는 대상의 결과 모름. 담당자가 대상 도구에서 확인한 결과를 남겨야 한다

    public static PublishResolution of(TaskItem item) {
        return item.mustRecheckOnPublish() && !item.getPublishTarget().recheckable() ? MANUAL_CHECK : RETRY;
    }
}
