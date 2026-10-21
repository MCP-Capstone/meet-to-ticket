package com.mcpcapstone.meettoticket.agent;

import static org.assertj.core.api.Assertions.*;

import com.mcpcapstone.meettoticket.analysis.*;
import com.mcpcapstone.meettoticket.common.JsonCodec;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.node.ObjectNode;

import java.time.*;
import java.util.*;

class AgentResultValidatorTest {
    private final JsonCodec json = new JsonCodec();
    private final AgentResultValidator validator = new AgentResultValidator();

    private AgentContracts.StartRequest request() {
        return new AgentContracts.StartRequest(
                UUID.randomUUID(),
                new AgentContracts.MeetingData(
                        1L,
                        "회의",
                        LocalDate.of(2026, 10, 22),
                        "Asia/Seoul",
                        List.of(11L),
                        "a.txt",
                        "원문 근거"),
                new AgentContracts.ProjectData(1L, "CSV", "MCP-Capstone", "mcp-capstone"),
                List.of(new AgentContracts.MemberData(11L, null, "박종민", "jongmin")));
    }

    private ObjectNode content() {
        return (ObjectNode)
                json.tree(
                        Map.of(
                                "todo",
                                List.of("수정"),
                                "acceptanceCriteria",
                                List.of("검증"),
                                "assignees",
                                List.of(
                                        Map.of(
                                                "projectMemberId",
                                                11,
                                                "githubName",
                                                "jongmin",
                                                "mappingStatus",
                                                "MATCHED")),
                                "evidence",
                                List.of(Map.of("startLine", 1, "endLine", 1, "quote", "원문 근거")),
                                "due",
                                Map.of(),
                                "revision",
                                1,
                                "review",
                                Map.of(
                                        "correctionCount",
                                        0,
                                        "reviewedRevision",
                                        1,
                                        "reason",
                                        "통과",
                                        "approvedPayload",
                                        Map.of(
                                                "owner",
                                                "MCP-Capstone",
                                                "repo",
                                                "mcp-capstone",
                                                "title",
                                                "수정",
                                                "body",
                                                "본문",
                                                "assignees",
                                                List.of("jongmin"))),
                                "publication",
                                Map.of(
                                        "verified",
                                        true,
                                        "publishedAt",
                                        OffsetDateTime.now().toString())));
    }

    private AgentContracts.Result result(AgentContracts.StartRequest r, ObjectNode c, String url) {
        return new AgentContracts.Result(
                r.taskId(),
                1L,
                TaskStatus.COMPLETED,
                TaskStage.VERIFYING,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of(
                        new AgentContracts.Item(
                                UUID.randomUUID(),
                                null,
                                "수정",
                                c,
                                "CREATE",
                                "실행 업무",
                                "APPROVED",
                                "SUCCEEDED",
                                1,
                                url,
                                null)),
                new AgentContracts.Summary(1, 1, 0, 1, 0, 0, 0),
                null);
    }

    @Test
    void 검증된_실제계정과_근거와_저장소의_결과를_허용한다() {
        var r = request();
        assertThatCode(
                        () ->
                                validator.validate(
                                        r,
                                        result(
                                                r,
                                                content(),
                                                "https://github.com/MCP-Capstone/mcp-capstone/issues/1"),
                                        false))
                .doesNotThrowAnyException();
    }

    @Test
    void 원문에_없는_근거와_다른저장소_URL을_차단한다() {
        var r = request();
        ObjectNode c = content();
        ((ObjectNode) c.path("evidence").get(0)).put("quote", "없는 내용");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        r,
                                        result(
                                                r,
                                                c,
                                                "https://github.com/MCP-Capstone/mcp-capstone/issues/1"),
                                        false))
                .isExactlyInstanceOf(AgentResultValidationException.class)
                .hasMessageContaining("원문에 없는 근거 인용");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        r,
                                        result(r, content(), "https://evil.example/issues/1"),
                                        false))
                .isExactlyInstanceOf(AgentResultValidationException.class)
                .hasMessageContaining("생성 성공의 이슈 URL 불일치");
    }

    @Test
    void 두번을_넘긴보완을_차단한다() {
        var r = request();
        ObjectNode c = content();
        ((ObjectNode) c.path("review")).put("correctionCount", 3);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        r,
                                        result(
                                                r,
                                                c,
                                                "https://github.com/MCP-Capstone/mcp-capstone/issues/1"),
                                        false))
                .isExactlyInstanceOf(AgentResultValidationException.class)
                .hasMessageContaining("보완 횟수·검수 버전·사유 오류");
    }

    @Test
    void 승인_저장소_오류는_다른_검증과_구분한다() {
        var r = request();
        ObjectNode c = content();
        ((ObjectNode) c.path("review").path("approvedPayload")).put("repo", "wrong-repo");
        assertThatThrownBy(() -> validator.validate(r,
                result(r, c, "https://github.com/MCP-Capstone/mcp-capstone/issues/1"), false))
                .isInstanceOf(AgentResultValidationException.class)
                .hasMessageContaining("승인 저장소 이름 불일치")
                .hasMessageNotContaining("wrong-repo");
    }
}
