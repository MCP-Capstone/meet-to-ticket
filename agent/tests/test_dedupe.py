"""중복 판단: 모델이 조회 도구를 골라 부르고 관계를 제출한다. 실제 OpenAI·MCP는 호출하지 않는다."""

import asyncio

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request, make_state, run_review
from nodes import AgentNodes
from runner import run
from test_issue_tools import FakeMcpTool, make_tools

pytestmark = pytest.mark.anyio

UNRELATED = ("submit_decision", {"relation": "UNRELATED", "reason": "후보 없음"})


class FakeToolChat:
    """도구를 부르는 가짜 채팅 모델.

    plans: {사용자 메시지에 들어 있는 문자열: [턴마다 부를 (도구 이름, 인자) ...]}
    정해 둔 턴이 끝나면 UNRELATED를 제출한다. 받은 메시지를 기록해 도구 결과가 모델에게 갔는지 본다.
    """

    def __init__(self, plans=None, delay: float = 0.0):
        self.plans = plans or {}
        self.delay = delay
        self.bound: list[str] = []
        self.tool_choice = None
        self.seen: dict[str, list] = {}
        self.active = self.max_active = 0

    def bind_tools(self, tools, **kwargs):
        self.bound = [t.name for t in tools]
        self.tool_choice = kwargs.get("tool_choice")
        return self

    async def ainvoke(self, messages, config=None):
        from langchain_core.messages import AIMessage

        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            if self.delay:
                await asyncio.sleep(self.delay)
            user = messages[1].content
            key = next((k for k in self.plans if k in user), None)
            self.seen[key or user] = list(messages)
            plan = self.plans.get(key, [])
            turn = sum(1 for m in messages if m.type == "ai")
            name, args = plan[turn] if turn < len(plan) else UNRELATED
            return AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": f"call-{turn}"}])
        finally:
            self.active -= 1


class ToolLLM(FakeLLM):
    def __init__(self, chat, **kw):
        super().__init__(**kw)
        self.chat = chat

    def tool_chat(self):
        return self.chat


async def run_case(settings, chat, request=None, tools=None, publisher=None):
    pub = publisher or FakePublisher()
    tools = tools or make_tools()[0]
    result = await run(request or make_request(), settings, llm=ToolLLM(chat), publisher=pub,
                       reporter=FakeReporter(), issue_tools=tools)
    return result, pub


def by_key(result):
    return {i.work_key: i.content_json["dedupe"] for i in result.items}


async def test_key_in_meeting_uses_keyword_search(settings):
    chat = FakeToolChat({"관련 키: AUTH-112": [
        ("search_issues", {"query": "AUTH-112"}),
        ("submit_decision", {"relation": "SAME", "issue_number": 7, "reason": "AUTH-112 토큰 갱신과 같은 작업"}),
    ]})
    result, _ = await run_case(settings, chat, make_request("30min_1"))
    d = by_key(result)["30m1-a1"]
    assert d["relation"] == "SAME" and d["issue_number"] == 7 and d["issue_state"] == "closed"
    # 닫힌 이슈는 다시 열지 않고 신규 발행하며 겹칠 수 있음을 표시한다
    assert d["handling"] == {"type": "CREATE", "issue_number": 7, "overlap": True, "judged": True}
    assert d["before"] is None and d["after"] is None
    assert d["tool_calls"] == [{"tool": "search_issues", "args": {"query": "AUTH-112"}}]
    # 모델에게는 조회 도구 3개와 제출 도구만 준다 (이슈 생성 도구 없음), 매 턴 도구를 부르게 한다
    assert chat.bound == ["search_similar_issues", "search_issues", "read_issue", "submit_decision"]
    assert chat.tool_choice == "required"
    # AUTH-112 키가 있는 티켓은 모두 키워드 검색을 고른다
    with_key = [d for d in by_key(result).values() if d and d["tool_calls"]]
    assert all(d["tool_calls"][0]["tool"] == "search_issues" for d in with_key)
    assert result.metrics["dedupe"]["judged"] == 12
    assert result.metrics["dedupe"]["toolCalls"] == sum(len(d["tool_calls"]) for d in with_key)


async def test_semantic_search_then_read_issue(settings):
    chat = FakeToolChat({"비동기 사용자 CSV 내보내기 API": [
        ("search_similar_issues", {"query": "CSV 내보내기 API"}),
        ("read_issue", {"number": 12}),
        ("submit_decision", {"relation": "CHANGE", "issue_number": 12, "reason": "같은 API지만 방식이 다름",
                             "before": "동기 처리", "after": "비동기 처리"}),
    ]})
    result, pub = await run_case(settings, chat)
    d = by_key(result)["10m1-a1"]
    assert d["relation"] == "CHANGE" and d["issue_number"] == 12 and d["issue_state"] == "open"
    # CHANGE + 열린 이슈는 갱신으로 제안하고, 카드와 갱신 섹션에 쓸 변경 전후 값을 남긴다 (TASK-043)
    assert (d["before"], d["after"]) == ("동기 처리", "비동기 처리")
    assert d["handling"] == {"type": "UPDATE", "issue_number": 12, "overlap": False, "judged": True}
    assert [c["tool"] for c in d["tool_calls"]] == ["search_similar_issues", "read_issue"]
    tool_results = [m.content for m in chat.seen["비동기 사용자 CSV 내보내기 API"] if m.type == "tool"]
    assert "CSV 빈 결과 오류 수정" in tool_results[0] and tool_results[1].startswith("제목: CSV")
    assert len(pub.calls) == 6  # CHANGE는 새로 발행한다 (본문에 이전 이슈 링크, test_dedupe_publish)


async def test_no_candidates_submits_without_tools(settings):
    result, _ = await run_case(settings, FakeToolChat())
    for d in by_key(result).values():
        if d is not None:
            assert d["relation"] == "UNRELATED" and d["tool_calls"] == [] and d["issue_number"] is None


async def test_tool_errors_are_shown_to_model(settings):
    chat = FakeToolChat({"관련 키: AUTH-112": [("search_issues", {"query": "AUTH-112"}), UNRELATED]})
    tools, _ = make_tools(keyword=FakeMcpTool(error="[GITHUB_RATE_LIMIT] 잠시 후 다시 시도"))
    await run_case(settings, chat, make_request("30min_1"), tools=tools)
    tool_results = [m.content for m in chat.seen["관련 키: AUTH-112"] if m.type == "tool"]
    assert tool_results == ["[GITHUB_RATE_LIMIT] 잠시 후 다시 시도"]


async def test_total_tool_limit(settings):
    chat = FakeToolChat({"비동기 사용자 CSV": [
        ("search_similar_issues", {"query": "a"}), ("search_issues", {"query": "b"}),
        ("search_similar_issues", {"query": "c"}), ("search_issues", {"query": "d"}),
        ("read_issue", {"number": 12}),
    ]})
    result, pub = await run_case(settings, chat)
    d = by_key(result)["10m1-a1"]
    assert d["relation"] is None and d["skipped"] == "tool_limit" and len(d["tool_calls"]) == 5
    assert len(pub.calls) == 6  # 판단이 없어도 발행은 그대로
    # 한도를 넘기면 판단 없이 신규 발행으로 처리한다 (TASK-041)
    assert d["handling"] == {"type": "CREATE", "issue_number": None, "overlap": False, "judged": False}


async def test_same_tool_limit(settings):
    chat = FakeToolChat({"비동기 사용자 CSV": [("search_similar_issues", {"query": str(i)}) for i in range(4)]})
    result, _ = await run_case(settings, chat)
    assert by_key(result)["10m1-a1"]["skipped"] == "same_tool_limit"


async def test_unseen_issue_number_is_discarded(settings):
    chat = FakeToolChat({"비동기 사용자 CSV": [
        ("submit_decision", {"relation": "SAME", "issue_number": 999, "reason": "같아 보임"})]})
    result, _ = await run_case(settings, chat)
    d = by_key(result)["10m1-a1"]
    assert d["relation"] is None and d["skipped"] == "unverified_issue"


async def test_invalid_decision_is_discarded(settings):
    chat = FakeToolChat({"비동기 사용자 CSV": [("submit_decision", {"relation": "MAYBE", "reason": "?"})]})
    result, _ = await run_case(settings, chat)
    assert by_key(result)["10m1-a1"]["skipped"] == "invalid_decision"


async def test_timeout_per_ticket(settings):
    settings.dedupe_timeout_seconds = 0.05
    result, pub = await run_case(settings, FakeToolChat(delay=0.3))
    assert {d["skipped"] for d in by_key(result).values() if d} == {"timeout"}
    assert {d["handling"]["type"] for d in by_key(result).values() if d} == {"CREATE"}
    assert len(pub.calls) == 6


async def test_concurrency_limit(settings):
    settings.dedupe_concurrency = 2
    chat = FakeToolChat(delay=0.02)
    await run_case(settings, chat)
    assert chat.max_active == 2  # 승인 6건을 2건씩


async def test_only_approved_tickets_are_judged(settings):
    result, _ = await run_case(settings, FakeToolChat())
    d = by_key(result)
    assert d["10m1-a2"] is None and d["10m1-a8"] is None  # 확인 필요(NEEDS_CHECK) 티켓은 판단하지 않음
    assert sum(1 for v in d.values() if v) == 6


async def test_rag_disabled_has_no_dedupe(settings):
    result = await run(make_request(), settings, llm=ToolLLM(FakeToolChat()), publisher=FakePublisher(),
                       reporter=FakeReporter())
    assert all(v is None for v in by_key(result).values()) and "dedupe" not in result.metrics


async def test_rag_down_marks_skipped_and_publishes(settings):
    settings.rag_enabled = True
    settings.rag_mcp_url = "http://127.0.0.1:9/mcp"
    pub = FakePublisher()
    result = await run(make_request(), settings, llm=ToolLLM(FakeToolChat()), publisher=pub, reporter=FakeReporter())
    assert {d["skipped"] for d in by_key(result).values() if d} == {"rag_unavailable"}
    assert len(pub.calls) == 6


async def test_deadline_skips_judging(settings):
    nodes = AgentNodes(ToolLLM(FakeToolChat()), FakePublisher(), FakeReporter(), settings, issue_tools=make_tools()[0])
    state, _ = await run_review(nodes, make_state())
    state.update(rag={"status": "ready"}, deadline_at=0)  # 이미 지난 마감
    out = await nodes.dedupe(state)
    assert {t["dedupe"]["skipped"] for t in out["tickets"] if t["dedupe"]} == {"deadline"}
    assert "error_code" not in out  # 판단만 건너뛰고 발행 단계로 간다


async def test_judge_input_uses_source_not_draft(settings):
    # 초안 설명은 넘기지 않고 회의록 원문의 할 일과 근거 인용을 넘긴다 (#107)
    chat = FakeToolChat()
    nodes = AgentNodes(ToolLLM(chat), FakePublisher(), FakeReporter(), settings, issue_tools=make_tools()[0])
    state, _ = await run_review(nodes, make_state())
    state["rag"] = {"status": "ready"}
    out = await nodes.dedupe(state)
    t = next(t for t in out["tickets"] if t["work_key"] == "10m1-a1")
    user = next(m for k, msgs in chat.seen.items() for m in msgs[1:2] if t["action_text"] in m.content).content
    assert f"할 일(회의록 원문): {t['action_text']}" in user
    assert all(q in user for q in t["evidence_quotes"])
    assert t["description"] not in user
