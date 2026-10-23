package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.user.User;

import tools.jackson.databind.JsonNode;

import java.util.*;

// Agent 결과 항목의 담당자를 프로젝트 멤버에 연결한다 (REQ-010).
// 멤버 정확히 1명과 연결될 때만 담당 멤버로 삼는다. 이름만 보고 GitHub 로그인명을 추정하지 않으며,
// GitHub 계정이 없는 멤버도 연결한다.
final class AssigneeResolver {
    private AssigneeResolver() {}

    // Slack 공지와 Notion 티켓은 담당자 대신 업로더에게 보낸다 (REQ-010, REQ-012).
    // 업로더가 프로젝트 멤버가 아니면 담당 지정 필요로 남는다.
    static Optional<ProjectMember> resolve(JsonNode content, List<ProjectMember> members, User uploader) {
        String target = content.path("publish_target").asText("GITHUB");
        if (!target.equals("SLACK") && !target.equals("NOTION")) return resolve(content, members);
        if (uploader == null) return Optional.empty();
        return members.stream()
                .filter(m -> m.getUser() != null && m.getUser().getId().equals(uploader.getId()))
                .findFirst();
    }

    static Optional<ProjectMember> resolve(JsonNode content, List<ProjectMember> members) {
        JsonNode assignees = content.path("assignees");
        Set<ProjectMember> found = new LinkedHashSet<>();
        if (assignees.isArray()) {
            // Mock와 이전 형식: [{projectMemberId, mentionedName, ...}]
            for (JsonNode a : assignees) {
                if (!a.hasNonNull("projectMemberId")) return Optional.empty();
                long id = a.path("projectMemberId").asLong();
                var member = members.stream().filter(m -> m.getId().equals(id)).findFirst();
                if (member.isEmpty()) return Optional.empty();
                found.add(member.get());
            }
        } else {
            // Agent: {names: [회의록 담당자 이름], github: [...], unresolved: [...]}
            for (JsonNode n : assignees.path("names")) {
                String name = n.asText("").trim();
                List<ProjectMember> same = members.stream().filter(m -> m.getName().trim().equals(name)).toList();
                // 이름이 멤버와 맞지 않거나 동명이인이면 담당자를 정할 수 없다.
                if (same.size() != 1) return Optional.empty();
                found.add(same.get(0));
            }
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }
}
