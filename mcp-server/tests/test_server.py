"""server.py 테스트. MCP 도구 등록 정보와, 도구 함수가 래퍼를 제대로 부르는지 확인한다."""

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

import github
import server


@pytest.fixture
def anyio_backend():
    # list_tools()가 async라 anyio로 돌린다 (trio는 쓰지 않음)
    return "asyncio"


@pytest.mark.anyio
async def test_tool_registered_with_annotations():
    # 도구 3개가 등록되고, create_issue는 쓰기 도구 힌트와 필수 입력값이 맞는지 확인한다
    tools = await server.mcp.list_tools()
    assert sorted(t.name for t in tools) == ["create_issue", "list_issues", "search_issues"]
    tool = next(t for t in tools if t.name == "create_issue").model_dump(by_alias=True)
    assert tool["annotations"]["readOnlyHint"] is False
    assert tool["annotations"]["destructiveHint"] is False
    assert set(tool["inputSchema"]["required"]) == {"owner", "repo", "title", "body"}
    # list_issues는 읽기 전용 도구로 표시된다
    read = next(t for t in tools if t.name == "list_issues").model_dump(by_alias=True)
    assert read["annotations"]["readOnlyHint"] is True
    assert set(read["inputSchema"]["required"]) == {"owner", "repo"}
    # search_issues도 읽기 전용이고, 검색어가 필수다
    search = next(t for t in tools if t.name == "search_issues").model_dump(by_alias=True)
    assert search["annotations"]["readOnlyHint"] is True
    assert set(search["inputSchema"]["required"]) == {"owner", "repo", "query"}


def test_tool_rejects_not_allowed_repo(monkeypatch):
    # 허용되지 않은 저장소는 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        server.create_issue("x", "y", "t", "b")


def test_tool_creates_issue(monkeypatch):
    # make_client를 가짜 transport로 바꿔 도구 → 래퍼 → 응답 흐름을 확인한다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    transport = httpx.MockTransport(
        lambda r: httpx.Response(201, json={"number": 1, "html_url": "u", "assignees": []})
    )
    real = github.make_client
    monkeypatch.setattr(github, "make_client", lambda: real(transport=transport))
    out = server.create_issue("a", "b", "t", "body")
    assert out["number"] == 1 and out["status"] == "created"



def test_list_issues_tool(monkeypatch):
    # 허용된 저장소면 {"issues": [...]}를 돌려주고, 아니면 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    transport = httpx.MockTransport(
        lambda r: httpx.Response(200, json=[{"number": 1, "title": "t", "state": "open", "html_url": "u"}])
    )
    real = github.make_client
    monkeypatch.setattr(github, "make_client", lambda: real(transport=transport))
    assert server.list_issues("a", "b")["issues"][0]["number"] == 1
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        server.list_issues("x", "y")


def test_search_issues_tool(monkeypatch):
    # 허용된 저장소면 {"issues": [...]}를 돌려주고, 아니면 GitHub 호출 전에 막힌다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "a/b")
    item = {"number": 7, "title": "AUTH-112", "state": "open", "html_url": "u",
            "repository_url": "https://api.github.com/repos/a/b", "closed_at": None}
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json={"items": [item]}))
    real = github.make_client
    monkeypatch.setattr(github, "make_client", lambda: real(transport=transport))
    out = server.search_issues("a", "b", "AUTH-112")
    assert out == {"issues": [{"number": 7, "title": "AUTH-112", "state": "open", "url": "u", "closed_at": None}]}
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        server.search_issues("x", "y", "AUTH-112")
