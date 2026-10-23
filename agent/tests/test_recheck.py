"""발행 직전 재대조 (TASK-042). 실제 OpenAI, MCP는 호출하지 않는다."""

import asyncio

import pytest

from recheck import recheck
from test_dedupe import FakeToolChat, ToolLLM
from test_issue_tools import FakeMcpTool, make_tools

pytestmark = pytest.mark.anyio


def ticket(key, action, handling):
    return {"work_key": key, "title": action, "action_text": action, "evidence_quotes": [f"{action}로 하죠"],
            "expected": {}, "handling": handling}


CREATE = {"type": "CREATE", "issue_number": None, "overlap": False, "judged": True}
SAME_12 = [("search_similar_issues", {"query": "CSV"}),
           ("submit_decision", {"relation": "SAME", "issue_number": 12, "reason": "같은 CSV 작업"})]


async def test_new_issue_becomes_link_when_same_open_issue_appears(settings):
    sync = FakeMcpTool({"indexed": 1, "skipped": 0})
    tools, _ = make_tools(sync_tool=sync)
    tickets = [ticket("a", "CSV 빈 결과 수정", CREATE), ticket("b", "로그인 화면 정리", CREATE)]
    original = [dict(t) for t in tickets]

    out = await recheck(tickets, issue_tools=tools, llm=ToolLLM(FakeToolChat({"CSV 빈 결과": SAME_12})),
                        settings=settings)

    a, b = out["tickets"]
    assert a["handling"] == {"type": "LINK", "issue_number": 12, "overlap": False, "judged": True, "changed": True}
    assert a["recheck"]["relation"] == "SAME" and a["recheck"]["issue_state"] == "open"
    assert b["handling"] == {**CREATE, "changed": False} and b["recheck"]["relation"] == "UNRELATED"
    # 저장소마다 색인 증분 동기화는 한 번만 한다
    assert sync.calls == [{"owner": "o", "repo": "r", "full": False}]
    assert out["sync"] == {"indexed": 1, "skipped": 0} and out["skipped"] is None
    assert tickets == original  # 입력은 바꾸지 않는다


async def test_confirmed_handling_is_kept_unless_recheck_links(settings):
    update = {"type": "UPDATE", "issue_number": 12, "overlap": False, "judged": True}
    chat = FakeToolChat({
        "관련 키 정리": [("search_issues", {"query": "AUTH-112"}),
                    ("submit_decision", {"relation": "SAME", "issue_number": 7, "reason": "닫힌 같은 작업"})],
        "CSV 비동기": [("submit_decision", {"relation": "UNRELATED", "reason": "후보 없음"})],
    })
    tools, _ = make_tools()
    out = await recheck([ticket("a", "관련 키 정리", CREATE), ticket("b", "CSV 비동기", update)],
                        issue_tools=tools, llm=ToolLLM(chat), settings=settings)

    a, b = out["tickets"]
    # 닫힌 이슈와 같은 작업이어도 다시 열지 않으므로 담당자가 확인한 신규 발행 그대로
    assert a["recheck"]["handling"]["type"] == "CREATE" and a["handling"] == {**CREATE, "changed": False}
    # 담당자가 확인한 갱신을 재대조의 다른 판정이 덮지 않는다
    assert b["handling"] == {**update, "changed": False}


async def test_link_to_same_issue_is_not_a_change(settings):
    link = {"type": "LINK", "issue_number": 12, "overlap": False, "judged": True}
    tools, _ = make_tools()
    out = await recheck([ticket("a", "CSV 빈 결과 수정", link)], issue_tools=tools,
                        llm=ToolLLM(FakeToolChat({"CSV 빈 결과": SAME_12})), settings=settings)
    assert out["tickets"][0]["handling"] == {**link, "changed": False}


async def test_failed_recheck_keeps_confirmed_handling(settings):
    settings.dedupe_timeout_seconds = 0.05
    tools, _ = make_tools()
    out = await recheck([ticket("a", "CSV 빈 결과 수정", CREATE)], issue_tools=tools,
                        llm=ToolLLM(FakeToolChat(delay=0.3)), settings=settings)
    t = out["tickets"][0]
    assert t["recheck"]["skipped"] == "timeout" and t["handling"] == {**CREATE, "changed": False}


async def test_without_rag_recheck_is_skipped(settings):
    out = await recheck([ticket("a", "CSV 빈 결과 수정", CREATE)], issue_tools=None,
                        llm=ToolLLM(FakeToolChat()), settings=settings)
    t = out["tickets"][0]
    assert out["skipped"] == "rag_unavailable" and t["recheck"]["skipped"] == "rag_unavailable"
    assert t["handling"] == {**CREATE, "changed": False}


async def test_slow_sync_does_not_block_recheck(settings):
    class SlowSync(FakeMcpTool):
        async def ainvoke(self, args):
            await asyncio.sleep(0.3)
            return await super().ainvoke(args)

    settings.rag_sync_timeout_seconds = 0.05
    tools, _ = make_tools(sync_tool=SlowSync({"indexed": 1}))
    out = await recheck([ticket("a", "CSV 빈 결과 수정", CREATE)], issue_tools=tools,
                        llm=ToolLLM(FakeToolChat({"CSV 빈 결과": SAME_12})), settings=settings)
    assert out["sync"] is None and out["tickets"][0]["handling"]["type"] == "LINK"


async def test_no_tickets(settings):
    assert await recheck([], issue_tools=None, llm=None, settings=settings) == {"tickets": [], "sync": None, "skipped": None}


async def test_edited_create_is_not_overridden_by_recheck_link(settings):
    edited = {**CREATE, "edited": True}
    tools, _ = make_tools()
    out = await recheck([ticket("a", "CSV 빈 결과 수정", edited)], issue_tools=tools,
                        llm=ToolLLM(FakeToolChat({"CSV 빈 결과": SAME_12})), settings=settings)
    assert out["tickets"][0]["handling"] == {**edited, "changed": False}


async def test_sync_error_does_not_break_recheck(settings):
    class BrokenSync(FakeMcpTool):
        async def ainvoke(self, args):
            raise ConnectionError("mcp closed")

    tools, _ = make_tools(sync_tool=BrokenSync({}))
    out = await recheck([ticket("a", "CSV 빈 결과 수정", CREATE)], issue_tools=tools,
                        llm=ToolLLM(FakeToolChat({"CSV 빈 결과": SAME_12})), settings=settings)
    assert out["sync"] is None and out["tickets"][0]["handling"]["type"] == "LINK"
