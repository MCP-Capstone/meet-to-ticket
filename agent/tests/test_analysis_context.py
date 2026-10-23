"""TASK-220: 기존 이슈·Notion 문서를 분석 맥락으로 수집한다."""

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from project_context import ProjectContextTools
from runner import initial_state, run

pytestmark = pytest.mark.anyio


class FakeTool:
    def __init__(self, response=None, error: Exception | None = None):
        self.response = response
        self.error = error
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        if self.error:
            raise self.error
        return self.response


def context_tools(*, fail_notion: bool = False):
    issues = FakeTool({"issues": [
        {"number": 7, "title": "기존 작업", "state": "open", "url": "https://example.test/7"},
        {"number": 3, "title": "종료 작업", "state": "closed"},
    ]})
    pages = FakeTool({"pages": [{"page_id": "page-a", "title": "요구사항"}, {"page_id": "page-b", "title": "회의 규칙"}]})
    reader = FakeTool(error=RuntimeError("Notion 권한 없음")) if fail_notion else FakeTool({
        "page_id": "page-a", "title": "요구사항", "url": "https://notion.so/page-a",
        "markdown": "# 요구사항\n기존 정책", "truncated": False,
    })
    return ProjectContextTools(issues, pages, reader, max_chars=1000), issues, pages, reader


async def test_context_tools_reads_existing_issues_and_notion_documents():
    tools, issues, pages, reader = context_tools()

    result = await tools.collect("MCP-Capstone", "meet-to-ticket", "https://notion.so/root")

    assert result["warnings"] == []
    assert [item["number"] for item in result["issues"]] == [7, 3]
    assert len(result["notionDocs"]) == 2
    assert issues.calls == [{"owner": "MCP-Capstone", "repo": "meet-to-ticket", "state": "all", "limit": 20}]
    assert pages.calls == [{"root_page_url_or_id": "https://notion.so/root"}]
    assert [call["page_url_or_id"] for call in reader.calls] == ["page-a", "page-b"]


async def test_context_failure_is_a_warning_and_does_not_stop_analysis(draft_settings):
    tools, _, _, _ = context_tools(fail_notion=True)
    request = make_request()
    request.project.notion_page_url = "https://notion.so/root"
    llm, reporter = FakeLLM(), FakeReporter()

    result = await run(
        request, draft_settings, llm=llm, publisher=FakePublisher(), reporter=reporter, context_tools=tools,
    )

    assert result.status == "SUCCEEDED"
    progress = reporter.progress_details[-1]["context"]
    assert progress["issues"] == 2 and progress["notionDocs"] == 0
    assert progress["warnings"] and progress["warnings"][0].startswith("Notion 문서 조회 실패")
    assert result.metrics["context"] == {
        "issues": 2, "notionDocs": 0, "warnings": progress["warnings"],
    }


async def test_loaded_context_reaches_draft_prompt_input(draft_settings):
    tools, _, _, _ = context_tools()
    request = make_request()
    request.project.notion_page_url = "https://notion.so/root"
    llm = FakeLLM()

    await run(request, draft_settings, llm=llm, publisher=FakePublisher(), reporter=FakeReporter(), context_tools=tools)

    context = llm.draft_contexts[0]["project_context"]
    assert context["issues"][0]["number"] == 7
    assert context["notionDocs"][0]["markdown"] == "# 요구사항\n기존 정책"


def test_initial_state_preserves_extended_project_and_member_context():
    request = make_request()
    request.project.notion_page_url = "https://notion.so/root"
    request.project_members[0].slack_email = "minjun@example.com"
    request.project_members[0].slack_user_id = "U123"
    request.project_members[0].role = "ADMIN"
    request.project_members[0].repository_permission = "GRANTED"

    state = initial_state(request)

    assert state["notion_page_url"] == "https://notion.so/root"
    assert state["members"][0] == {
        "project_member_id": None, "user_id": None, "name": "김민준", "github_name": "minjun-k",
        "slack_email": "minjun@example.com", "slack_user_id": "U123", "role": "ADMIN",
        "repository_permission": "GRANTED",
    }
