"""중복 판단용 조회 도구와 rag-mcp 연결. 실제 MCP 서버·GitHub·OpenAI는 호출하지 않는다."""

import json

import pytest

from conftest import PUBLISHED_10MIN, FakeLLM, FakePublisher, FakeReporter, make_request
from issue_tools import IssueTools
from runner import run

pytestmark = pytest.mark.anyio


class FakeMcpTool:
    """MCP 도구 대신 쓰는 가짜. 받은 인자를 기록하고 정해 둔 결과를 돌려주거나 MCP 오류 문구로 실패한다."""

    def __init__(self, result=None, error: str | None = None):
        self.result = result
        self.error = error
        self.calls: list[dict] = []

    async def ainvoke(self, args):
        self.calls.append(args)
        if self.error:
            raise RuntimeError(f"Error executing tool: {self.error}")
        return json.dumps(self.result, ensure_ascii=False)


SIMILAR = {"repository": "o/r", "results": [
    {"number": 12, "title": "CSV 빈 결과 오류 수정", "state": "open", "url": "https://github.com/o/r/issues/12",
     "score": 0.91234, "snippet": "가" * 500, "closed_at": None},
]}
KEYWORD = {"issues": [{"number": 7, "title": "AUTH-112 토큰 갱신", "state": "closed",
                       "url": "https://github.com/o/r/issues/7", "closed_at": "2026-10-01T00:00:00Z"}]}


def make_tools(**kw):
    reads: list[str] = []

    async def reader(uri):
        reads.append(uri)
        return "제목: CSV 빈 결과 오류 수정\n상태: open\n" + "본문 " * 2000

    params = dict(similar=FakeMcpTool(SIMILAR), keyword=FakeMcpTool(KEYWORD), reader=reader,
                  sync_tool=FakeMcpTool({"indexed": 3, "skipped": 10}), index_tool=FakeMcpTool({"indexed": True}))
    params.update(kw)
    return IssueTools("o", "r", **params), reads


async def test_model_tools_hide_repository():
    tools, _ = make_tools()
    judge = {t.name: t for t in tools.judge_tools()}
    assert set(judge) == {"search_similar_issues", "search_issues", "read_issue"}
    for t in judge.values():
        props = t.args_schema.model_json_schema()["properties"]
        assert "owner" not in props and "repo" not in props  # 모델은 저장소를 정할 수 없다
    assert set(judge["search_similar_issues"].args_schema.model_json_schema()["properties"]) == {"query", "k", "state"}


async def test_similar_search_fixes_repository_and_trims():
    similar = FakeMcpTool(SIMILAR)
    tools, _ = make_tools(similar=similar)
    out = json.loads(await tools.judge_tools()[0].ainvoke({"query": "CSV 0건 오류"}))
    assert similar.calls == [{"owner": "o", "repo": "r", "query": "CSV 0건 오류", "k": 5, "state": "all"}]
    hit = out["results"][0]
    assert hit["number"] == 12 and hit["score"] == 0.912 and len(hit["snippet"]) == 200


async def test_keyword_search_and_resource_read():
    keyword = FakeMcpTool(KEYWORD)
    tools, reads = make_tools(keyword=keyword, max_chars=300)
    judge = {t.name: t for t in tools.judge_tools()}
    out = json.loads(await judge["search_issues"].ainvoke({"query": "AUTH-112"}))
    assert keyword.calls[0] == {"owner": "o", "repo": "r", "query": "AUTH-112", "state": "all", "limit": 5}
    assert out["issues"][0]["number"] == 7
    text = await judge["read_issue"].ainvoke({"number": 12})
    assert reads == ["issue://o/r/12"]  # Resource를 도구로 감싸 읽는다
    # 이슈 본문은 데이터 태그로 감싸 넘기고, 길이 한도는 태그 안의 본문에 적용한다 (#265)
    assert text.startswith("<existing_issue>\n") and text.endswith("(길어서 생략됨)\n</existing_issue>")
    assert len(text) <= 300 + len("<existing_issue>\n\n</existing_issue>")


async def test_errors_are_returned_to_model_as_text():
    tools, _ = make_tools(similar=FakeMcpTool(error="[GITHUB_RATE_LIMIT] 잠시 후 다시 시도"))
    assert await tools.search_similar_issues("x") == "[GITHUB_RATE_LIMIT] 잠시 후 다시 시도"


async def test_empty_index_means_no_candidates():
    tools, _ = make_tools(similar=FakeMcpTool(error="[INDEX_EMPTY] o/r 색인이 비어 있음"))
    assert json.loads(await tools.search_similar_issues("x"))["results"] == []


async def test_missing_keyword_tool_is_left_out():
    tools, _ = make_tools(keyword=None)
    assert [t.name for t in tools.judge_tools()] == ["search_similar_issues", "read_issue"]


async def test_sync_and_index_are_code_only():
    sync, index = FakeMcpTool({"indexed": 3, "skipped": 10}), FakeMcpTool({"indexed": True})
    tools, _ = make_tools(sync_tool=sync, index_tool=index)
    assert "sync_issues" not in [t.name for t in tools.judge_tools()]
    assert await tools.sync() == {"indexed": 3, "skipped": 10}
    assert sync.calls == [{"owner": "o", "repo": "r", "full": False}]
    assert await tools.sync(recheck_empty=True) == {"indexed": 3, "skipped": 10}
    assert sync.calls[-1] == {"owner": "o", "repo": "r", "full": False, "recheck_empty": True}
    assert await tools.index(15) is True and index.calls == [{"owner": "o", "repo": "r", "number": 15}]
    failing, _ = make_tools(sync_tool=FakeMcpTool(error="[EMBEDDING_ERROR] x"), index_tool=FakeMcpTool(error="[GITHUB_ERROR] y"))
    assert await failing.sync() is None and await failing.index(1) is False  # 실패해도 예외 없이 계속


async def test_tools_run_in_toolnode():
    """A2에서 쓸 LangGraph ToolNode로 실행되고, 도구 오류도 ToolMessage로 모델에게 돌아간다."""
    from langchain_core.messages import AIMessage
    from langgraph.graph import END, START, MessagesState, StateGraph
    from langgraph.prebuilt import ToolNode

    tools, _ = make_tools(keyword=FakeMcpTool(error="[GITHUB_ERROR] 검색 실패"))
    g = StateGraph(MessagesState)
    g.add_node("tools", ToolNode(tools.judge_tools(), handle_tool_errors=True))
    g.add_edge(START, "tools")
    g.add_edge("tools", END)
    msg = AIMessage(content="", tool_calls=[
        {"name": "search_similar_issues", "args": {"query": "CSV"}, "id": "c1"},
        {"name": "search_issues", "args": {"query": "AUTH-112"}, "id": "c2"},
    ])
    out = await g.compile().ainvoke({"messages": [msg]})
    contents = {m.tool_call_id: m.content for m in out["messages"][1:]}
    assert json.loads(contents["c1"])["results"][0]["number"] == 12
    assert contents["c2"] == "[GITHUB_ERROR] 검색 실패"


async def test_runner_rag_disabled(settings):
    result = await run(make_request(), settings, llm=FakeLLM(), publisher=FakePublisher(), reporter=FakeReporter())
    assert result.metrics["rag"] == {"status": "disabled", "sync": None}


async def test_runner_continues_when_rag_is_down(settings):
    settings.rag_enabled = True
    settings.rag_mcp_url = "http://127.0.0.1:9/mcp"  # 아무것도 듣지 않는 포트
    pub = FakePublisher()
    result = await run(make_request(), settings, llm=FakeLLM(), publisher=pub, reporter=FakeReporter())
    assert result.status == "SUCCEEDED" and len(pub.calls) == len(PUBLISHED_10MIN)  # 중복 확인 없이 기존처럼 발행
    assert result.metrics["rag"]["status"] == "unavailable"


async def test_runner_syncs_index_once(settings):
    sync = FakeMcpTool({"indexed": 2, "skipped": 5, "updated_at": "2026-10-21T06:00:00Z"})
    tools, _ = make_tools(sync_tool=sync)
    result = await run(make_request(), settings, llm=FakeLLM(), publisher=FakePublisher(), reporter=FakeReporter(),
                       issue_tools=tools)
    # 분석 경로만 변경 없음 응답을 한 번 더 확인하도록 요청한다 (발행 전 재대조는 test_recheck에서 기본값 확인)
    assert sync.calls == [{"owner": "o", "repo": "r", "full": False, "recheck_empty": True}]
    assert result.metrics["rag"] == {"status": "ready", "sync": {"indexed": 2, "skipped": 5, "updated_at": "2026-10-21T06:00:00Z"}}
