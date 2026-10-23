package com.mcpcapstone.meettoticket.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

// Agent 없이 저장 흐름을 확인하는 모의 구현. 이름에 denied가 들어가면 DENIED, error가 들어가면 ERROR, 그 외는 GRANTED.
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockRepositoryPermissionGateway implements RepositoryPermissionGateway {
    @Override
    public List<MemberPermission> check(String githubOwner, String githubRepo, List<String> githubNames) {
        return githubNames.stream()
                .map(n -> {
                    String lower = n.toLowerCase();
                    return new MemberPermission(
                            n, lower.contains("denied") ? "DENIED" : lower.contains("error") ? "ERROR" : "GRANTED");
                })
                .toList();
    }
}
