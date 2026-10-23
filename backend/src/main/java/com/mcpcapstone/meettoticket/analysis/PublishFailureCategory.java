package com.mcpcapstone.meettoticket.analysis;

import java.util.Set;

// 발행 실패 사유의 큰 분류. 저장소 권한 문제는 PM이 프로젝트 설정에서 풀어야 하므로 화면이 설정으로 안내한다 (REQ-019).
public enum PublishFailureCategory {
    REPOSITORY_PERMISSION, // 저장소 권한 문제
    OTHER; // 그 밖의 실패

    private static final Set<String> PERMISSION_CODES = Set.of("REPOSITORY_NOT_ALLOWED", "GITHUB_AUTH", "GITHUB_FORBIDDEN");

    // 실패 코드가 없으면 분류하지 않는다.
    public static PublishFailureCategory of(String errorCode) {
        if (errorCode == null) return null;
        return PERMISSION_CODES.contains(errorCode) ? REPOSITORY_PERMISSION : OTHER;
    }
}
