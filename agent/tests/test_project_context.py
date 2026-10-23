"""프로젝트 맥락 MCP 수집 단위 테스트."""

from __future__ import annotations

import json

import pytest

from project_context import ProjectContextTools


class FakeTool:
    def __init__(self, result):
        self.result = result
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        return json.dumps(self.result)


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
