"""server.py와 GitHub·Notion·Slack 도구 테스트.

도구 등록 정보와, 도구 함수가 저장소 확인 → api 호출을 제대로 하는지 확인한다.
"""

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

import server
from integrations.github import api, tools
from integrations.notion import api as notion_api
from integrations.notion import tools as notion_tools
from integrations.slack import api as slack_api
from integrations.slack import tools as slack_tools


@pytest.fixture
def anyio_backend():
    # list_tools()가 async라 anyio로 돌린다 (trio는 쓰지 않음)
    return "asyncio"


@pytest.fixture
def fake_github(monkeypatch):
    """api.make_client를 가짜 transport로 바꾼다. handler(request) -> httpx.Response를 넣어 쓴다."""
    real = api.make_client

    def use(handler):
        monkeypatch.setattr(api, "make_client", lambda: real(transport=httpx.MockTransport(handler)))

    return use


@pytest.fixture
def fake_notion(monkeypatch):
    """Notion 클라이언트도 MockTransport로 바꾼다."""
    real = notion_api.make_client
    monkeypatch.setenv("NOTION_TOKEN", "test-token")

    def use(handler):
        monkeypatch.setattr(notion_api, "make_client", lambda: real(transport=httpx.MockTransport(handler)))

    return use


@pytest.fixture
def fake_slack(monkeypatch):
    """slack_api.make_client를 가짜 transport로 바꾼다."""
    monkeypatch.setenv("SLACK_BOT_TOKEN", "test-token")
    real = slack_api.make_client

    def use(handler):
        monkeypatch.setattr(slack_api, "make_client", lambda: real(transport=httpx.MockTransport(handler)))

    return use


@pytest.mark.anyio
async def test_tools_registered_with_annotations():
    # GitHub 8개, Notion 4개, Slack 3개가 등록되고, 쓰기·읽기 힌트와 필수 입력값이 맞는지 확인한다
    tools_ = {t.name: t.model_dump(by_alias=True) for t in await server.mcp.list_tools()}
    assert sorted(tools_) == [
        "add_comment", "check_member_permissions", "check_notion_page", "check_slack_channel", "create_issue", "create_page",
        "find_marker", "list_issues", "list_labels", "list_pages", "lookup_slack_user", "post_message", "read_page", "search_issues", "update_issue"
    ]

    create = tools_["create_issue"]
    assert create["annotations"]["readOnlyHint"] is False
    assert create["annotations"]["idempotentHint"] is False
    assert create["annotations"]["title"] == "GitHub 이슈 생성"
    assert set(create["inputSchema"]["required"]) == {"owner", "repo", "title", "body"}

    comment = tools_["add_comment"]
    assert comment["annotations"]["readOnlyHint"] is False
    assert set(comment["inputSchema"]["required"]) == {"owner", "repo", "number", "body"}

    update = tools_["update_issue"]
    assert update["annotations"]["readOnlyHint"] is False
    assert set(update["inputSchema"]["required"]) == {"owner", "repo", "number", "append_section"}

    read = tools_["list_issues"]
    assert read["annotations"]["readOnlyHint"] is True
    assert set(read["inputSchema"]["required"]) == {"owner", "repo"}

    members = tools_["check_member_permissions"]
    assert members["annotations"]["readOnlyHint"] is True
    assert set(members["inputSchema"]["required"]) == {"owner", "repo", "github_names"}

    labels = tools_["list_labels"]
    assert labels["annotations"]["readOnlyHint"] is True
    assert set(labels["inputSchema"]["required"]) == {"owner", "repo"}

    search = tools_["search_issues"]
    assert search["annotations"]["readOnlyHint"] is True
    assert set(search["inputSchema"]["required"]) == {"owner", "repo", "query"}

    page = tools_["check_notion_page"]
    assert page["annotations"]["readOnlyHint"] is True
    assert set(page["inputSchema"]["required"]) == {"page_url_or_id"}

    create_page = tools_["create_page"]
    assert create_page["annotations"]["readOnlyHint"] is False
    assert set(create_page["inputSchema"]["required"]) == {"root_page_url_or_id", "title", "markdown"}

    channel = tools_["check_slack_channel"]
    assert channel["annotations"]["readOnlyHint"] is True
    assert set(channel["inputSchema"]["required"]) == {"channel"}

    user = tools_["lookup_slack_user"]
    assert user["annotations"]["readOnlyHint"] is True
    assert set(user["inputSchema"]["required"]) == {"email"}

    message = tools_["post_message"]
    assert message["annotations"]["readOnlyHint"] is False
    assert message["annotations"]["idempotentHint"] is False
    assert set(message["inputSchema"]["required"]) == {"channel_id", "text"}


def test_tool_rejects_not_allowed_repo(monkeypatch):
    # 허용 목록에 값이 있으면 그 밖의 저장소는 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        tools.create_issue("x", "y", "t", "b")


def test_tool_creates_issue_without_allowlist(monkeypatch, fake_github):
    # 허용 목록이 비어 있으면 백엔드가 넘긴 저장소에 그대로 만든다
    monkeypatch.delenv("GITHUB_ALLOWED_REPOS", raising=False)
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        return httpx.Response(201, json={"number": 1, "html_url": "u", "assignees": []})

    fake_github(handler)
    out = tools.create_issue("any-owner", "any-repo", "t", "body")
    assert seen["path"] == "/repos/any-owner/any-repo/issues"
    assert out == {"status": "created", "number": 1, "url": "u", "assignees": [], "dropped_assignees": [],
                   "dropped_labels": []}


def test_comment_and_update_tools(monkeypatch, fake_github):
    # 쓰기 도구도 허용 저장소 검사를 거친 뒤 각 GitHub 경로로 보낸다.
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")

    def comment_handler(request):
        assert request.url.path == "/repos/a/b/issues/2/comments"
        return httpx.Response(201, json={"id": 1, "html_url": "comment-url"})

    fake_github(comment_handler)
    assert tools.add_comment("a", "b", 2, "same") == {"status": "commented", "comment_id": 1, "url": "comment-url"}

    calls = []
    def update_handler(request):
        calls.append(request.method)
        if request.method == "GET":
            return httpx.Response(200, json={"body": "old", "labels": []})
        return httpx.Response(200, json={"number": 2, "html_url": "issue-url"})

    fake_github(update_handler)
    assert tools.update_issue("a", "b", 2, "## change")["url"] == "issue-url"
    assert calls == ["GET", "PATCH"]
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        tools.add_comment("x", "y", 2, "same")


def test_list_labels_tool(monkeypatch, fake_github):
    # 허용된 저장소면 {"labels": [...]}를 돌려주고, 아니면 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    fake_github(lambda r: httpx.Response(200, json=[{"name": "bug", "description": None}]))
    assert tools.list_labels("a", "b") == {"labels": [{"name": "bug", "description": ""}]}
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        tools.list_labels("x", "y")


def test_list_issues_tool(monkeypatch, fake_github):
    # 허용된 저장소면 {"issues": [...]}를 돌려주고, 아니면 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    fake_github(lambda r: httpx.Response(200, json=[{"number": 1, "title": "t", "state": "open", "html_url": "u"}]))
    assert tools.list_issues("a", "b")["issues"][0]["number"] == 1
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        tools.list_issues("x", "y")


def test_search_issues_tool(monkeypatch, fake_github):
    # 허용된 저장소면 {"issues": [...]}를 돌려주고, 아니면 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    item = {"number": 7, "title": "AUTH-112", "state": "open", "html_url": "u",
            "repository_url": "https://api.github.com/repos/a/b", "closed_at": None}
    fake_github(lambda r: httpx.Response(200, json={"items": [item]}))
    out = tools.search_issues("a", "b", "AUTH-112")
    assert out == {"issues": [{"number": 7, "title": "AUTH-112", "state": "open", "url": "u", "closed_at": None}]}
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        tools.search_issues("x", "y", "AUTH-112")


def test_check_member_permissions_tool(monkeypatch, fake_github):
    # 허용된 저장소면 {"members": [...]}를 돌려주고, 아니면 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    fake_github(lambda r: httpx.Response(200, json={}) if r.url.path == "/repos/a/b" else httpx.Response(204))
    assert tools.check_member_permissions("a", "b", ["alice"]) == {
        "members": [{"github_name": "alice", "permission": "GRANTED"}]
    }
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        tools.check_member_permissions("x", "y", ["alice"])


def test_notion_tools(fake_notion):
    def handler(request):
        if request.url.path.endswith("/children"):
            return httpx.Response(200, json={"results": [], "has_more": False})
        if request.method == "POST":
            return httpx.Response(200, json={"id": "page-id", "url": "page-url"})
        return httpx.Response(200, json={"id": "page-id", "url": "page-url", "properties": {"이름": {"type": "title", "title": []}}})

    fake_notion(handler)
    assert notion_tools.check_notion_page("11111111111111111111111111111111")["page_id"] == "page-id"
    assert notion_tools.list_pages("11111111111111111111111111111111") == {"pages": []}


def test_slack_tools_open_the_client_and_return_api_results(fake_slack):
    def channel_handler(request):
        return httpx.Response(200, json={"ok": True, "channel": {"id": "C12345678", "name": "project", "is_member": True}})

    fake_slack(channel_handler)
    assert slack_tools.check_slack_channel("C12345678")["status"] == "CONNECTED"
