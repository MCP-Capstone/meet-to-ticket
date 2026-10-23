"""server.py와 GitHub 도구(integrations/github/tools.py) 테스트.

도구 등록 정보와, 도구 함수가 저장소 확인 → api 호출을 제대로 하는지 확인한다.
"""

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

import server
from integrations.github import api, tools


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


@pytest.mark.anyio
async def test_tools_registered_with_annotations():
    # 도구 5개가 등록되고, 쓰기·읽기 힌트와 필수 입력값이 맞는지 확인한다
    tools_ = {t.name: t.model_dump(by_alias=True) for t in await server.mcp.list_tools()}
    assert sorted(tools_) == ["check_member_permissions", "create_issue", "list_issues", "list_labels", "search_issues"]

    create = tools_["create_issue"]
    assert create["annotations"]["readOnlyHint"] is False
    assert create["annotations"]["idempotentHint"] is False
    assert create["annotations"]["title"] == "GitHub 이슈 생성"
    assert set(create["inputSchema"]["required"]) == {"owner", "repo", "title", "body"}

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
