package com.mcpcapstone.meettoticket.agent;

import com.mcpcapstone.meettoticket.analysis.*;
import com.mcpcapstone.meettoticket.common.JsonCodec;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.node.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component("agentTransport")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockAgentGateway implements AgentGateway {
    private final AgentProperties properties;
    private final JsonCodec json;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    private record Run(
            AgentContracts.StartRequest request, OffsetDateTime createdAt, AtomicInteger reads) {}

    @Override
    public String mode() {
        return "MOCK";
    }

    @Override
    public AgentContracts.Accepted submit(AgentContracts.StartRequest request) {
        Run run = new Run(request, OffsetDateTime.now(), new AtomicInteger());
        if (runs.putIfAbsent(request.taskId(), run) != null) throw new AgentException(false);
        return new AgentContracts.Accepted(
                request.taskId(), TaskStatus.QUEUED, null, run.createdAt(), null);
    }

    @Override
    public AgentContracts.Result status(UUID taskId) {
        Run run = runs.get(taskId);
        if (run == null) throw new AgentException(false);
        if (run.reads().getAndIncrement() == 0)
            return new AgentContracts.Result(
                    taskId,
                    run.request().meeting().meetingId(),
                    TaskStatus.RUNNING,
                    TaskStage.REVIEWING,
                    run.createdAt(),
                    null,
                    List.of(),
                    null,
                    null,
                    progress(run));
        String scenario = properties.getMockScenario();
        List<AgentContracts.Item> items =
                switch (scenario) {
                    case "empty" -> List.of();
                    case "rejected" -> List.of(item(run, "BLOCKED", 0));
                    case "failed" -> List.of(item(run, "FAILED", 0));
                    case "partial" -> List.of(item(run, "SKIPPED", 0), item(run, "FAILED", 1));
                    // Phase 1 흐름: 모의 승인 뒤 발행 생략
                    case "approved" -> List.of(item(run, "SKIPPED", 0));
                    // 기본: 발행 전 초안 (기획_v16, 회의록 처리는 초안 저장에서 멈춤)
                    default -> List.of(item(run, AgentRunMapper.DRAFT, 0));
                };
        int approved =
                (int) items.stream().filter(i -> i.reviewStatus().equals("APPROVED")).count();
        int failed = (int) items.stream().filter(i -> i.publishStatus().equals("FAILED")).count();
        int skipped = (int) items.stream().filter(i -> i.publishStatus().equals("SKIPPED")).count();
        int drafts = (int) items.stream().filter(i -> i.publishStatus().equals(AgentRunMapper.DRAFT)).count();
        int rejected = (int) items.stream().filter(i -> i.reviewStatus().equals("REJECTED")).count();
        TaskStatus status =
                failed == 0
                        ? TaskStatus.COMPLETED
                        : skipped > 0 ? TaskStatus.PARTIALLY_FAILED : TaskStatus.FAILED;
        return new AgentContracts.Result(
                taskId,
                run.request().meeting().meetingId(),
                status,
                items.isEmpty() ? TaskStage.EXTRACTING : TaskStage.VERIFYING,
                run.createdAt(),
                OffsetDateTime.now(),
                items,
                new AgentContracts.Summary(
                        items.size(), approved, rejected, 0, failed, 0, skipped, drafts),
                null,
                null);
    }

    // 진행 화면 개발용 고정 세부 정보 (실제 Agent의 progress와 같은 형태)
    private ObjectNode progress(Run run) {
        ObjectNode progress = (ObjectNode) json.tree(Map.of("step", "EXTRACTING"));
        ObjectNode context = progress.putObject("context");
        context.put("members", run.request().projectMembers().size());
        context.putNull("issues");
        context.putNull("notionDocs");
        ObjectNode extraction = progress.putObject("extraction");
        extraction.put("todos", 1);
        extraction.put("decisions", 0);
        extraction.put("openQuestions", 0);
        return progress;
    }

    private AgentContracts.Item item(Run run, String publish, int index) {
        var request = run.request();
        var member =
                request.projectMembers().stream()
                        .filter(
                                m ->
                                        request.meeting()
                                                .participantIds()
                                                .contains(m.projectMemberId()))
                        .findFirst()
                        // 처리 요청 API는 참석자를 받지 않는다. 그때는 프로젝트의 첫 멤버를 모의 담당자로 쓴다
                        .or(() -> request.projectMembers().stream().findFirst())
                        .orElseThrow();
        boolean rejected = publish.equals("BLOCKED");
        boolean draft = publish.equals(AgentRunMapper.DRAFT);
        String title = "[모의 실행] 후속 업무 " + (index + 1);
        ObjectNode content =
                (ObjectNode)
                        json.tree(
                                Map.of(
                                        "type",
                                        "task",
                                        "background",
                                        "Agent 없이 백엔드 저장 흐름을 확인하는 고정 모의 데이터입니다.",
                                        "todo",
                                        List.of("실제 추출·검수·GitHub 발행을 수행하지 않습니다."),
                                        "acceptanceCriteria",
                                        List.of("화면과 DB에서 모의 실행 결과 확인"),
                                        "revision",
                                        rejected ? 3 : 1));
        ObjectNode assignee = content.objectNode();
        assignee.put("projectMemberId", member.projectMemberId());
        assignee.put("mentionedName", member.name());
        if (member.githubName() == null) assignee.putNull("githubName");
        else assignee.put("githubName", member.githubName());
        assignee.put("mappingStatus", "MOCK");
        content.set("assignees", json.tree(List.of(assignee)));
        ObjectNode due = content.objectNode();
        due.putNull("date");
        due.putNull("time");
        due.putNull("qualifier");
        due.putNull("originalText");
        content.set("due", due);
        String first = request.meeting().originalContent().split("\\R", -1)[0];
        content.set(
                "evidence",
                json.tree(
                        List.of(
                                Map.of(
                                        "startLine",
                                        1,
                                        "endLine",
                                        1,
                                        "quote",
                                        first,
                                        "sectionId",
                                        "MOCK"))));
        ObjectNode review = content.putObject("review");
        review.put("correctionCount", rejected ? 2 : 0);
        review.put("reviewedRevision", rejected ? 3 : 1);
        review.put(
                "reason",
                rejected ? "[MOCK] 보완 2회 후 반려 시나리오" : "[MOCK] 검수 통과를 가정한 결과. 실제 LLM 검수가 아닙니다.");
        if (rejected) review.putNull("approvedPayload");
        else
            review.set(
                    "approvedPayload",
                    json.tree(
                            Map.of(
                                    "owner",
                                    request.project().githubOwner(),
                                    "repo",
                                    request.project().githubRepo(),
                                    "title",
                                    title,
                                    "body",
                                    "[MOCK] 실제 발행 없음",
                                    "assignees",
                                    List.of())));
        content.set("publication", json.tree(Map.of("mock", true, "verified", false)));
        return new AgentContracts.Item(
                UUID.nameUUIDFromBytes(
                        (request.taskId() + ":" + index)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                null,
                title,
                content,
                "CREATE",
                "[MOCK] 실제 추출 대신 고정 시나리오",
                rejected ? "REJECTED" : draft ? AgentRunMapper.PENDING : "APPROVED",
                publish,
                null,
                null,
                publish.equals("FAILED") ? "[MOCK] 발행 실패 시나리오. 실제 GitHub 호출 없음" : null);
    }
}
