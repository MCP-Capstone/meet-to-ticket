"""프로젝트 맥락 반영 (TASK-019): Notion 기획 문서, 기존 이슈 수, 진행 화면의 맥락 파악."""

import asyncio
import json
from dataclasses import replace

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from context import DOC_CHARS, TOTAL_CHARS, ProjectContext, load_notion, relevant_pages
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

    def __init__(self, texts, error_for=(), slow_for=()):
        self.texts = texts
        self.error_for = set(error_for)
        self.slow_for = set(slow_for)
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        if args["page_url_or_id"] in self.slow_for:
            await asyncio.sleep(5)
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


def test_relevant_pages_ignore_common_words_and_partial_english():
    pages = [{"page_id": "a", "title": "개발 회의 기획 정리"},   # 흔한 단어만 있는 제목
             {"page_id": "b", "title": "UI 가이드"},             # "UI"가 "build"에 걸리면 안 된다
             {"page_id": "c", "title": "Build 파이프라인"}]
    source = "개발 회의에서 기획을 정리했다. CI build를 고친다."
    assert [p["page_id"] for p in relevant_pages(pages, source)] == ["c"]


async def test_load_notion_stops_at_total_limit():
    pages = [{"page_id": f"p{i}", "title": f"CSV 문서 {i}"} for i in range(5)]
    tools, reader = notion_tools(pages, texts={p["page_id"]: "가" * 9000 for p in pages})
    ctx = ProjectContext()
    await load_notion(ctx, tools["list_pages"], tools["read_page"], "url", "CSV 회의")
    count, docs = ctx.notion_docs, ctx.docs
    assert count == 5
    # 문서당 4,000자, 전체 12,000자에서 멈춘다 (AI-MCP 합의)
    assert [len(d["text"]) for d in docs] == [DOC_CHARS] * 3 and sum(len(d["text"]) for d in docs) == TOTAL_CHARS
    assert [c["max_chars"] for c in reader.calls] == [DOC_CHARS] * 3


async def test_load_notion_failures_do_not_stop_the_run():
    tools, _ = notion_tools(error_for={"p2"})
    ctx = ProjectContext()
    await load_notion(ctx, tools["list_pages"], tools["read_page"], "url", SOURCE)
    assert ctx.notion_docs == 3 and [d["title"] for d in ctx.docs] == ["관리자 권한 정책"]  # 못 읽은 문서는 건너뛴다
    broken = FakeMcpTool(error="[NOTION_ERROR] 페이지에 접근할 수 없습니다")
    ctx = ProjectContext()
    await load_notion(ctx, broken, tools["read_page"], "url", SOURCE)
    assert ctx.notion_docs is None and ctx.docs == []


async def test_planning_docs_go_to_draft_and_progress(draft_settings):
    llm, rep = FakeLLM(), FakeReporter()
    tools, _ = notion_tools()
    issue_tools = make_tools(status_tool=FakeMcpTool({"count": 128}))[0]
    result = await run(request_with_notion(), draft_settings, llm=llm, publisher=FakePublisher(), reporter=rep,
                       issue_tools=issue_tools, notion_tools=tools)
    assert result.status == "SUCCEEDED"
    project_context = llm.draft_contexts[0]["project_context"]
    assert project_context["issues"] == []
    assert [doc["title"] for doc in project_context["notionDocs"]] == [page["title"] for page in PAGES]
    # 진행 화면의 맥락 파악: 불러오기 전에 먼저 보고하고, 실제로 읽은 문서 수를 채운다
    contexts = [p["context"] for p in rep.progress_details if p["step"] == "CONTEXT"]
    assert contexts[0] == {"members": 6, "issues": None, "notionDocs": None}
    assert contexts[1]["members"] == 6
    assert contexts[1]["issues"] == 0
    assert contexts[1]["notionDocs"] == 3
    assert len(contexts[1]["warnings"]) == 1
    # 근거는 여전히 회의록 원문에서만 나온다
    source = make_request().meeting.original_content
    assert all(q in source for i in result.items for q in i.content_json.get("evidence_quotes", []))


async def test_without_notion_runs_as_before(draft_settings):
    llm, rep = FakeLLM(), FakeReporter()
    tools, reader = notion_tools()
    await run(make_request(), draft_settings, llm=llm, publisher=FakePublisher(), reporter=rep, notion_tools=tools)
    assert reader.calls == [] and tools["list_pages"].calls == []  # 기획 페이지가 없으면 부르지 않는다
    assert llm.draft_contexts[0]["project_context"] == {"issues": [], "notionDocs": [], "warnings": []}
    assert rep.progress_details[-1]["context"] == {"members": 6, "issues": 0, "notionDocs": 0, "warnings": []}


async def test_slow_context_is_skipped(draft_settings):
    s = replace(draft_settings, context_timeout_seconds=0.05)
    llm = FakeLLM()
    result = await run(request_with_notion(), s, llm=llm, publisher=FakePublisher(), reporter=FakeReporter(),
                       notion_tools={"list_pages": SlowTool(), "read_page": SlowTool()})
    assert result.status == "SUCCEEDED" and llm.draft_contexts[0]["project_context"]["notionDocs"] == []


async def test_timeout_keeps_documents_already_read(draft_settings):
    """두 번째 문서를 읽다가 시간 한도에 걸려도 목록 수와 이미 읽은 첫 문서는 남는다."""
    s = replace(draft_settings, context_timeout_seconds=0.2)
    tools, _ = notion_tools(slow_for={"p3"})
    llm, rep = FakeLLM(), FakeReporter()
    await run(request_with_notion(), s, llm=llm, publisher=FakePublisher(), reporter=rep, notion_tools=tools)
    docs = llm.draft_contexts[0]["project_context"]["notionDocs"]
    assert [d["page_id"] for d in docs] == ["p1", "p2"]  # 시간 초과 전에 읽은 문서는 버리지 않는다
    assert any("끝나지 않음" in w for w in rep.progress_details[-1]["context"]["warnings"])
    assert rep.progress_details[-1]["context"]["notionDocs"] == 2


async def test_context_step_is_reported_before_slow_loading(draft_settings):
    """색인 갱신·맥락 불러오기가 오래 걸려도 진행 화면에는 "맥락 파악"이 먼저 보인다."""
    rep = FakeReporter()
    seen_before_list = []

    class WatchingList(FakeMcpTool):
        async def ainvoke(self, args):
            seen_before_list.extend(p["step"] for p in rep.progress_details)
            return await super().ainvoke(args)

    tools, _ = notion_tools()
    tools["list_pages"] = WatchingList({"pages": PAGES})
    await run(request_with_notion(), draft_settings, llm=FakeLLM(), publisher=FakePublisher(), reporter=rep,
              notion_tools=tools)
    assert seen_before_list == ["CONTEXT"]


def test_review_does_not_flag_background_from_planning_docs(settings):
    nodes = AgentNodes(None, None, None, settings)
    t = {"title": "CSV 내보내기", "description": "조직 관리자만 쓰는 기능이다. 분기별 감사 대상이다.",
         "tasks": [], "acceptance_criteria": []}
    check = ReviewCheck(item=4, passed=False, quote="분기별 감사 대상이다", problem="원문에 없음", fix="삭제")
    # 회의록에는 없지만 기획 문서에 있는 배경 문구는 지적으로 인정하지 않는다
    assert nodes._verify_checks(t, [check], "회의록 원문", [], planning="### 정책\n분기별 감사 대상이다.") == []
    assert len(nodes._verify_checks(t, [check], "회의록 원문", [])) == 1
