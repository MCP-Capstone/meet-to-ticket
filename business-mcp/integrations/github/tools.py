"""GitHub MCP 도구.

도구 함수는 저장소 확인 → 클라이언트 열기 → api 함수 호출만 한다. GitHub 호출과 오류 변환은 api.py에 있다.
owner/repo는 에이전트 코드가 백엔드 요청의 프로젝트 저장소로 넣는다 (모델이 고르지 않음).
"""

from __future__ import annotations

from typing import Any, Literal

from mcp.server.mcpserver import MCPServer

from common import READ, WRITE, titled
from integrations.github import api


def create_issue(
    owner: str,
    repo: str,
    title: str,
    body: str,
    labels: list[str] | None = None,
    assignees: list[str] | None = None,
) -> dict[str, Any]:
    """새 이슈를 만들고 번호·URL·실제 지정된 담당자를 돌려준다. 실패하면 "[코드] 메시지" ToolError."""
    api.check_allowed(owner, repo)
    # 요청마다 클라이언트를 열고 닫는다 (호출이 드물어 연결을 유지할 필요가 없음)
    with api.make_client() as client:
        return api.create_issue(client, owner, repo, title, body, labels, assignees)


def list_issues(owner: str, repo: str, state: str = "open", limit: int = 10) -> dict[str, Any]:
    """최근 이슈 목록을 {"issues": [...]}로 돌려준다. 에이전트의 MCP 연결 확인에 쓴다."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return {"issues": api.list_issues(client, owner, repo, state, limit)}


def search_issues(
    owner: str, repo: str, query: str, state: Literal["open", "closed", "all"] = "all", limit: int = 5
) -> dict[str, Any]:
    """검색 결과를 {"issues": [{number, title, state, url, closed_at}]}로 돌려준다 (최대 20개)."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return {"issues": api.search_issues(client, owner, repo, query, state, limit)}


def register(mcp: MCPServer) -> None:
    """GitHub 도구를 서버에 등록한다."""
    mcp.tool(
        description=(
            "GitHub 저장소에 새 이슈를 만든다. "
            "담당자 지정이 거부되면 담당자 없이 생성하고 dropped_assignees로 알려 준다. "
            "다시 부르면 이슈가 하나 더 생긴다."
        ),
        annotations=titled(WRITE, "GitHub 이슈 생성"),
    )(create_issue)
    mcp.tool(
        description="GitHub 저장소의 최근 이슈 목록을 조회한다 (PR 제외).",
        annotations=titled(READ, "GitHub 이슈 목록 조회"),
    )(list_issues)
    mcp.tool(
        description=(
            "저장소 안에서 검색어가 들어간 이슈를 찾는다 (PR 제외). "
            "회의록에 나온 이슈 키(예: AUTH-112)나 기능 이름처럼 정확한 단어로 찾을 때 쓴다. "
            "표현이 달라도 같은 작업인지 찾을 때는 rag-mcp의 search_similar_issues를 쓴다. "
            "결과의 state(open/closed)와 closed_at으로 진행 중인 작업인지 끝난 작업인지 구분한다."
        ),
        annotations=titled(READ, "GitHub 이슈 키워드 검색"),
    )(search_issues)
