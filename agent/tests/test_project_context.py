"""프로젝트 맥락 MCP 수집 단위 테스트."""

from __future__ import annotations

import asyncio
import json

import pytest

import project_context
from project_context import ProjectContextTools


class FakeTool:
    def __init__(self, result):
        self.result = result
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        return json.dumps(self.result)


class SlowReadTool(FakeTool):
    async def ainvoke(self, args):
        self.calls.append(args)
        if args["page_url_or_id"] == "slow":
            await asyncio.sleep(0.05)
        return json.dumps(self.result[args["page_url_or_id"]])


@pytest.mark.asyncio
async def test_기존_이슈와_Notion_하위_문서를_분석_맥락으로_수집한다():
    issues = FakeTool({"issues": [{"number": 18, "title": "기존 작업", "state": "open", "url": "https://github/x"}]})
    pages = FakeTool({"pages": [{"page_id": "p1", "title": "기획"}]})
    read = FakeTool({"page_id": "p1", "title": "기획", "url": "https://notion/p1", "markdown": "기획 본문"})

    result = await ProjectContextTools(issues, pages, read, max_chars=1000).collect("org", "repo", "https://notion/root")

    assert result == {
        "issues": [{"number": 18, "title": "기존 작업", "state": "open", "url": "https://github/x", "closed_at": None}],
        "notionDocs": [{"page_id": "p1", "title": "기획", "url": "https://notion/p1", "markdown": "기획 본문", "truncated": False}],
        "warnings": [],
    }
    assert issues.calls == [{"owner": "org", "repo": "repo", "state": "all", "limit": 20}]
    assert pages.calls == [{"root_page_url_or_id": "https://notion/root"}]
    assert read.calls == [{"page_url_or_id": "p1", "max_chars": 1000}]


@pytest.mark.asyncio
async def test_느린_Notion_문서는_건너뛰고_먼저_읽은_문서를_쓴다(monkeypatch):
    monkeypatch.setattr("project_context.NOTION_PAGE_TIMEOUT_SECONDS", 0.01)
    pages = FakeTool({"pages": [{"page_id": "fast", "title": "빠른 문서"}, {"page_id": "slow", "title": "느린 문서"}]})
    read = SlowReadTool({
        "fast": {"page_id": "fast", "title": "빠른 문서", "url": "https://notion/fast", "markdown": "빠른 본문"},
        "slow": {"page_id": "slow", "title": "느린 문서", "url": "https://notion/slow", "markdown": "느린 본문"},
    })

    result = await ProjectContextTools(None, pages, read, max_chars=1000).collect("org", "repo", "https://notion/root")

    assert [doc["page_id"] for doc in result["notionDocs"]] == ["fast"]
    assert any("느린 문서" in warning and "생략됨" in warning for warning in result["warnings"])


@pytest.mark.asyncio
async def test_네번째_문서도_기존_조회_범위에_포함한다():
    pages = FakeTool({"pages": [{"page_id": f"p{n}", "title": f"문서 {n}"} for n in range(4)]})
    read = FakeTool({"markdown": "참고 본문"})

    result = await ProjectContextTools(None, pages, read, max_chars=1000).collect("org", "repo", "root")

    assert len(result["notionDocs"]) == 4
    assert {call["page_url_or_id"] for call in read.calls} == {"p0", "p1", "p2", "p3"}
    assert not any("분석 맥락에서 생략됨" in warning for warning in result["warnings"])


@pytest.mark.asyncio
async def test_조회_상한을_넘은_문서는_생략_안내를_남긴다():
    pages = FakeTool({"pages": [{"page_id": f"p{n}"} for n in range(11)]})
    read = FakeTool({"markdown": "참고 본문"})

    result = await ProjectContextTools(None, pages, read, max_chars=1000).collect("org", "repo", "root")

    assert len(result["notionDocs"]) == 10
    assert "Notion 문서 1건은 분석 맥락에서 생략됨" in result["warnings"]


class DelayedReadTool(FakeTool):
    """문서마다 다른 시간 뒤에 응답하고, 동시에 읽는 수의 최댓값을 기록한다."""

    def __init__(self, delays):
        super().__init__({})
        self.delays = delays
        self.active = 0
        self.max_active = 0

    async def ainvoke(self, args):
        self.calls.append(args)
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            await asyncio.sleep(self.delays[args["page_url_or_id"]])
        finally:
            self.active -= 1
        return json.dumps({"page_id": args["page_url_or_id"], "markdown": "본문"})


@pytest.mark.asyncio
async def test_Notion_문서는_끝난_순서와_관계없이_목록_순서를_유지한다():
    pages = FakeTool({"pages": [{"page_id": "p0"}, {"page_id": "p1"}, {"page_id": "p2"}]})
    # 동시에 읽는 두 문서 중 뒤 문서가 먼저 끝나고, 세 번째 문서는 그보다 늦게 끝난다
    read = DelayedReadTool({"p0": 0.05, "p1": 0.0, "p2": 0.0})

    result = await ProjectContextTools(None, pages, read, max_chars=1000).collect("org", "repo", "root")

    assert [call["page_url_or_id"] for call in read.calls][:2] == ["p0", "p1"]
    assert [doc["page_id"] for doc in result["notionDocs"]] == ["p0", "p1", "p2"]


@pytest.mark.asyncio
async def test_Notion_문서는_동시_조회_수를_제한한다():
    pages = FakeTool({"pages": [{"page_id": f"p{n}"} for n in range(10)]})
    read = DelayedReadTool({f"p{n}": 0.01 for n in range(10)})

    result = await ProjectContextTools(None, pages, read, max_chars=1000).collect("org", "repo", "root")

    assert len(result["notionDocs"]) == 10
    assert read.max_active == project_context.NOTION_READ_CONCURRENCY
