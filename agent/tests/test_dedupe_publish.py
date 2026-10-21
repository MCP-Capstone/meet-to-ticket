"""중복 판단 결과에 따른 발행: 같은 작업의 열린 이슈는 연결, 나머지는 새로 발행하고 관련 이슈를 본문에 남긴다.

실제 OpenAI·MCP·GitHub는 호출하지 않는다.
"""

import json
from urllib.parse import urlparse

import pytest

from conftest import FakePublisher, FakeReporter, make_request
from publisher import DryRunPublisher, build_issue_body, dedupe_link
from runner import run
from test_dedupe import FakeToolChat, ToolLLM, by_key, run_case
from test_issue_tools import FakeMcpTool, make_tools

pytestmark = pytest.mark.anyio

CSV = "비동기 사용자 CSV 내보내기 API"  # 10min_1 샘플 10m1-a1 티켓
AUTH = "관련 키: AUTH-112"  # 30min_1 샘플에서 AUTH-112 키가 있는 티켓들


def judged(relation, number, first=("search_similar_issues", {"query": "CSV"})):
    """후보를 찾고 관계를 제출하는 계획. #12는 열린 이슈(유사 검색), #7은 닫힌 이슈(키워드 검색)."""
    return [first, ("submit_decision", {"relation": relation, "issue_number": number, "reason": "r"})]


def body_of(pub, work_key):
    return next(c["body"] for c in pub.calls if f"work_key: {work_key} " in c["body"])


def item(result, work_key):
    return next(i for i in result.items if i.work_key == work_key)


async def test_same_open_issue_is_linked_not_created(settings):
    result, pub = await run_case(settings, FakeToolChat({CSV: judged("SAME", 12)}))
    i = item(result, "10m1-a1")
    assert i.publish_status == "SUCCEEDED" and i.github_issue_number == 12
    assert i.github_issue_url == "https://github.com/MCP-Capstone/mcp-capstone/issues/12"
    assert i.reason == "기존 이슈 #12과 같은 작업이라 새로 만들지 않음"
    assert i.content_json["dedupe"]["linked_existing"] is True
    assert all("work_key: 10m1-a1 " not in c["body"] for c in pub.calls)  # 새 이슈를 만들지 않음
    assert len(pub.calls) == 5
    assert result.summary["published"] == 6 and result.summary["linked"] == 1
    # 백엔드 검증 조건: 생성 성공은 번호 > 0, 프로젝트 저장소의 github.com https URL
    url = urlparse(i.github_issue_url)
    assert i.action == "CREATE" and url.scheme == "https" and url.netloc == "github.com"
    assert url.path == "/MCP-Capstone/mcp-capstone/issues/12" and not url.query and not url.fragment


async def test_same_closed_issue_publishes_new_with_previous_link(settings):
    chat = FakeToolChat({AUTH: judged("SAME", 7, first=("search_issues", {"query": "AUTH-112"}))})
    result, pub = await run_case(settings, chat, make_request("30min_1"))
    linked = [k for k, d in by_key(result).items() if d and d["issue_number"] == 7]
    assert linked and result.summary["linked"] == 0  # 닫힌 이슈는 끝난 일이라 연결하지 않음
    for key in linked:
        assert item(result, key).github_issue_number != 7
        assert "- 이전 작업: #7" in body_of(pub, key)


@pytest.mark.parametrize("relation,number,first,line", [
    ("CHANGE", 12, ("search_similar_issues", {"query": "CSV"}), "- #12과 다른 변경"),
    ("CHANGE", 7, ("search_issues", {"query": "CSV"}), "- 변경 전 작업: #7"),
    ("RELATED", 12, ("search_similar_issues", {"query": "CSV"}), "- 관련 이슈: #12"),
    ("RELATED", 7, ("search_issues", {"query": "CSV"}), "- 관련 이슈: #7"),
])
async def test_other_relations_publish_new_with_link(settings, relation, number, first, line):
    result, pub = await run_case(settings, FakeToolChat({CSV: judged(relation, number, first)}))
    i = item(result, "10m1-a1")
    assert i.publish_status == "SUCCEEDED" and i.github_issue_number not in (7, 12)
    body = body_of(pub, "10m1-a1")
    assert "## 관련" in body and line in body
    assert len(pub.calls) == 6 and result.summary["linked"] == 0


async def test_no_decision_publishes_without_link(settings):
    chat = FakeToolChat({CSV: [("submit_decision", {"relation": "SAME", "issue_number": 999, "reason": "?"})]})
    result, pub = await run_case(settings, chat)
    assert by_key(result)["10m1-a1"]["skipped"] == "unverified_issue"
    assert item(result, "10m1-a1").publish_status == "SUCCEEDED"
    assert "#999" not in body_of(pub, "10m1-a1")


def test_dedupe_link_lines():
    assert dedupe_link(None) is None
    assert dedupe_link({"relation": "UNRELATED", "issue_number": None}) is None
    assert dedupe_link({"relation": None, "issue_number": None, "skipped": "timeout"}) is None
    ticket = {"action_text": "a", "dedupe": {"relation": "RELATED", "issue_number": 3, "issue_state": "open"}}
    body = build_issue_body(ticket, "m", "")
    assert "## 관련\n- 관련 이슈: #3" in body  # 회의록 예상 처리·관련 키가 없어도 관련 절을 만든다


async def test_new_issues_are_indexed(settings):
    index = FakeMcpTool({"indexed": True})
    tools, _ = make_tools(index_tool=index)
    result, pub = await run_case(settings, FakeToolChat({CSV: judged("SAME", 12)}), tools=tools)
    created = sorted(i.github_issue_number for i in result.items if i.publish_status == "SUCCEEDED")
    assert sorted(c["number"] for c in index.calls) == [n for n in created if n != 12]  # 연결한 이슈는 색인하지 않음
    assert all(c["owner"] == "o" and c["repo"] == "r" for c in index.calls)  # 도구에 고정한 저장소
    assert result.metrics["dedupe"]["indexed"] == 5


async def test_index_failure_keeps_publish_result(settings):
    tools, _ = make_tools(index_tool=FakeMcpTool(error="[EMBEDDING_ERROR] 임베딩 실패"))
    result, pub = await run_case(settings, FakeToolChat(), tools=tools)
    published = [i for i in result.items if i.publish_status == "SUCCEEDED"]
    assert len(published) == 6 and all(i.content_json["dedupe"]["indexed"] is False for i in published)
    assert result.metrics["dedupe"]["indexed"] == 0 and result.error_code is None


async def test_dry_run_links_but_does_not_create_or_index(settings):
    index = FakeMcpTool({"indexed": True})
    tools, _ = make_tools(index_tool=index)
    result = await run(make_request(), settings, llm=ToolLLM(FakeToolChat({CSV: judged("SAME", 12)})),
                       publisher=DryRunPublisher(), reporter=FakeReporter(), issue_tools=tools)
    statuses = {i.work_key: i.publish_status for i in result.items if i.review_status == "APPROVED"}
    assert statuses.pop("10m1-a1") == "SUCCEEDED"  # 연결은 GitHub에 쓰지 않으므로 dry run에서도 한다
    assert set(statuses.values()) == {"SKIPPED"} and index.calls == []


class RepoIndex:
    """색인 흉내: 발행된 이슈를 index_issue로 받아 두고, 같은 제목으로 유사 검색하면 돌려준다."""

    def __init__(self, pub: FakePublisher):
        self.pub = pub
        self.titles: dict[int, str] = {}
        self.similar = self._Tool(self._search)
        self.index = self._Tool(self._index)

    class _Tool:
        def __init__(self, fn):
            self.fn = fn

        async def ainvoke(self, args):
            return json.dumps(self.fn(args), ensure_ascii=False)

    def _index(self, args):
        call = self.pub.calls[args["number"] - 101]  # FakePublisher 번호는 101부터
        self.titles[args["number"]] = call["title"]
        return {"indexed": True}

    def _search(self, args):
        return {"results": [{"number": n, "title": t, "state": "open", "score": 0.97, "snippet": ""}
                            for n, t in self.titles.items() if t == args["query"]]}


class TitleChat(FakeToolChat):
    """제목으로 유사 검색하고, 결과가 있으면 SAME, 없으면 UNRELATED를 제출하는 모델."""

    async def ainvoke(self, messages, config=None):
        from langchain_core.messages import AIMessage

        turn = sum(1 for m in messages if m.type == "ai")
        if turn == 0:
            title = next(line for line in messages[1].content.splitlines() if line.startswith("제목: "))[4:]
            call = {"name": "search_similar_issues", "args": {"query": title}, "id": "c0"}
        else:
            hits = json.loads(messages[-1].content)["results"]
            args = ({"relation": "SAME", "issue_number": hits[0]["number"], "reason": "같은 제목의 열린 이슈"}
                    if hits else {"relation": "UNRELATED", "reason": "후보 없음"})
            call = {"name": "submit_decision", "args": args, "id": "c1"}
        return AIMessage(content="", tool_calls=[call])


async def test_same_meeting_twice_links_instead_of_creating(settings):
    pub = FakePublisher()
    index = RepoIndex(pub)
    tools, _ = make_tools(similar=index.similar, index_tool=index.index)

    first = await run(make_request(), settings, llm=ToolLLM(TitleChat()), publisher=pub,
                      reporter=FakeReporter(), issue_tools=tools)
    created = {i.work_key: i.github_issue_number for i in first.items if i.publish_status == "SUCCEEDED"}
    assert len(created) == 6 and first.summary["linked"] == 0 and len(index.titles) == 6

    again = make_request().model_copy(update={"task_id": "task-again"})  # 같은 회의록, 새 작업
    second = await run(again, settings, llm=ToolLLM(TitleChat()), publisher=pub,
                       reporter=FakeReporter(), issue_tools=tools)
    linked = {i.work_key: i.github_issue_number for i in second.items if i.publish_status == "SUCCEEDED"}
    assert linked == created  # 첫 실행에서 만든 이슈로 연결
    assert len(pub.calls) == 6 and second.summary["linked"] == 6  # 새 이슈는 생기지 않음


async def test_slow_index_does_not_block_publish(settings, monkeypatch):
    import asyncio

    import nodes

    class SlowIndex:
        calls = 0

        async def ainvoke(self, args):
            SlowIndex.calls += 1
            await asyncio.sleep(1)

    monkeypatch.setattr(nodes, "INDEX_TIMEOUT_SECONDS", 0.01)
    tools, _ = make_tools(index_tool=SlowIndex())
    result, _ = await run_case(settings, FakeToolChat(), tools=tools)
    assert SlowIndex.calls == 6 and result.metrics["dedupe"]["indexed"] == 0
    assert sum(1 for i in result.items if i.publish_status == "SUCCEEDED") == 6
