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
    # 도구 2개가 등록되고, create_issue는 쓰기 도구 힌트와 필수 입력값이 맞는지 확인한다
    tools = await server.mcp.list_tools()
    assert sorted(t.name for t in tools) == ["create_issue", "list_issues"]
    tool = next(t for t in tools if t.name == "create_issue").model_dump(by_alias=True)
    assert tool["annotations"]["readOnlyHint"] is False
    assert tool["annotations"]["destructiveHint"] is False
    assert set(tool["inputSchema"]["required"]) == {"owner", "repo", "title", "body"}


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

