"""프로젝트 맥락 반영 (TASK-019): Notion 기획 문서, 기존 이슈 수, 진행 화면의 맥락 파악."""

import asyncio
import json
from dataclasses import replace

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from context import DOC_CHARS, TOTAL_CHARS, load_notion, relevant_pages
from nodes import AgentNodes
from runner import run
from schemas import ReviewCheck
from test_issue_tools import FakeMcpTool, make_tools

pytestmark = pytest.mark.anyio

PAGES = [
    {"page_id": "p1", "title": "팀 회식 공지"},             # 회의록과 관계없음
    {"page_id": "p2", "title": "CSV 내보내기 기획"},        # 단어 2개가 회의록에 나옴
    {"page_id": "p3", "title": "관리자 권한 정책"},          # 단어 1개
]


class FakeReader:
    """page_id마다 정해 둔 본문을 max_chars만큼 돌려주는 가짜 read_page."""

    def __init__(self, texts, error_for=()):
        self.texts = texts
        self.error_for = set(error_for)
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        if args["page_url_or_id"] in self.error_for:
            raise RuntimeError("Error executing tool: [NOTION_ERROR] 읽기 실패")
        text = self.texts[args["page_url_or_id"]]
        return json.dumps({"markdown": text[: args["max_chars"]], "truncated": len(text) > args["max_chars"]})


class SlowTool:
    async def ainvoke(self, args):
        await asyncio.sleep(1)


def notion_tools(pages=PAGES, texts=None, **kw):
    reader = FakeReader(texts or {p["page_id"]: f"{p['title']} 본문" for p in pages}, **kw)
    return {"list_pages": FakeMcpTool({"pages": pages}), "read_page": reader}, reader


def request_with_notion(url="https://www.notion.so/plan-123"):
    req = make_request()
    req.project.notion_page_url = url
    return req


SOURCE = "관리자 화면에서 CSV 내보내기를 만든다. 권한은 조직 관리자만."


def test_relevant_pages_by_title_words_in_meeting():
    # 조사가 붙어도("내보내기를") 단어가 회의록에 들어 있으면 관련 문서다
    assert [p["page_id"] for p in relevant_pages(PAGES, SOURCE)] == ["p2", "p3"]
    assert relevant_pages(PAGES, "점심 메뉴 회의") == []


async def test_load_notion_stops_at_total_limit():
    pages = [{"page_id": f"p{i}", "title": f"CSV 문서 {i}"} for i in range(5)]
    tools, reader = notion_tools(pages, texts={p["page_id"]: "가" * 9000 for p in pages})
    count, docs = await load_notion(tools["list_pages"], tools["read_page"], "url", "CSV 회의")
    assert count == 5
    # 문서당 4,000자, 전체 12,000자에서 멈춘다 (AI-MCP 합의)
    assert [len(d["text"]) for d in docs] == [DOC_CHARS] * 3 and sum(len(d["text"]) for d in docs) == TOTAL_CHARS
    assert [c["max_chars"] for c in reader.calls] == [DOC_CHARS] * 3


async def test_load_notion_failures_do_not_stop_the_run():
    tools, _ = notion_tools(error_for={"p2"})
    count, docs = await load_notion(tools["list_pages"], tools["read_page"], "url", SOURCE)
    assert count == 3 and [d["title"] for d in docs] == ["관리자 권한 정책"]  # 못 읽은 문서는 건너뛴다
    broken = FakeMcpTool(error="[NOTION_ERROR] 페이지에 접근할 수 없습니다")
    assert await load_notion(broken, tools["read_page"], "url", SOURCE) == (None, [])


async def test_planning_docs_go_to_draft_and_progress(draft_settings):
    llm, rep = FakeLLM(), FakeReporter()
    tools, _ = notion_tools()
    issue_tools = make_tools(status_tool=FakeMcpTool({"count": 128}))[0]
    result = await run(request_with_notion(), draft_settings, llm=llm, publisher=FakePublisher(), reporter=rep,
                       issue_tools=issue_tools, notion_tools=tools)
    assert result.status == "SUCCEEDED"
    planning = llm.draft_contexts[0]["planning"]
    assert "### CSV 내보내기 기획" in planning and "팀 회식" not in planning
    # 진행 화면의 맥락 파악: 기존 이슈 수와 기획 문서 수
    assert rep.progress_details[0]["context"] == {"members": 6, "issues": 128, "notionDocs": 3}
    # 근거는 여전히 회의록 원문에서만 나온다
    source = make_request().meeting.original_content
    assert all(q in source for i in result.items for q in i.content_json.get("evidence_quotes", []))


async def test_without_notion_runs_as_before(draft_settings):
    llm, rep = FakeLLM(), FakeReporter()
    tools, reader = notion_tools()
    await run(make_request(), draft_settings, llm=llm, publisher=FakePublisher(), reporter=rep, notion_tools=tools)
    assert reader.calls == [] and tools["list_pages"].calls == []  # 기획 페이지가 없으면 부르지 않는다
    assert llm.draft_contexts[0]["planning"] == ""
    assert rep.progress_details[0]["context"] == {"members": 6, "issues": None, "notionDocs": None}


async def test_slow_context_is_skipped(draft_settings):
    s = replace(draft_settings, context_timeout_seconds=0.05)
    llm = FakeLLM()
    result = await run(request_with_notion(), s, llm=llm, publisher=FakePublisher(), reporter=FakeReporter(),
                       notion_tools={"list_pages": SlowTool(), "read_page": SlowTool()})
    assert result.status == "SUCCEEDED" and llm.draft_contexts[0]["planning"] == ""


def test_review_does_not_flag_background_from_planning_docs(settings):
    nodes = AgentNodes(None, None, None, settings)
    t = {"title": "CSV 내보내기", "description": "조직 관리자만 쓰는 기능이다. 분기별 감사 대상이다.",
         "tasks": [], "acceptance_criteria": []}
    check = ReviewCheck(item=4, passed=False, quote="분기별 감사 대상이다", problem="원문에 없음", fix="삭제")
    # 회의록에는 없지만 기획 문서에 있는 배경 문구는 지적으로 인정하지 않는다
    assert nodes._verify_checks(t, [check], "회의록 원문", [], planning="### 정책\n분기별 감사 대상이다.") == []
    assert len(nodes._verify_checks(t, [check], "회의록 원문", [])) == 1
