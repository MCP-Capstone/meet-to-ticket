package com.mcpcapstone.meettoticket.agent;

import java.util.List;

// Agent POST /projects/check-members. 프로젝트 저장 시 멤버별 저장소 권한을 확인한다.
// 전체 실패(Agent/MCP 오류)는 AgentException으로 알린다.
public interface RepositoryPermissionGateway {
    List<MemberPermission> check(String githubOwner, String githubRepo, List<String> githubNames);

    // permission은 GRANTED, DENIED, ERROR 중 하나다.
    record MemberPermission(String githubName, String permission) {}
}
