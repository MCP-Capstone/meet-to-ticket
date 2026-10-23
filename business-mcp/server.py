"""meet-to-ticket MCP 서버 (이슈 생성, 이슈 목록 조회, 이슈 키워드 검색 도구).

실행: python server.py  →  http://127.0.0.1:8000/mcp (Streamable HTTP)
환경 변수: GITHUB_TOKEN, GITHUB_OWNER, GITHUB_REPO, (선택) GITHUB_ALLOWED_REPOS, MCP_HOST, MCP_PORT

현재(1차)는 토큰과 허용 저장소를 .env에서 읽고, 에이전트가 넘긴 owner/repo가 허용 목록에 있을 때만 이슈를 만든다.
추후 백엔드 API로 프로젝트별 저장소·토큰을 받아 원하는 저장소에 만들 수 있게 바꿀 예정이다 (github.py 참고).
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Literal

from dotenv import load_dotenv
from mcp.server.mcpserver import MCPServer
from mcp.types import ToolAnnotations

import github

# 터미널에 같은 이름의 환경 변수가 있어도 business-mcp/.env 값이 우선한다
load_dotenv(Path(__file__).resolve().parent / ".env", override=True)

# 에이전트가 http://<MCP_HOST>:<MCP_PORT>/mcp 로 연결해 아래 도구를 부른다
mcp = MCPServer("meet-to-ticket")


@mcp.tool(
    description=(
        "GitHub 저장소에 새 이슈를 만든다. 허용된 저장소만 가능하다. "
        "담당자 지정이 거부되면 담당자 없이 생성하고 dropped_assignees로 알려 준다."
    ),
    # 도구 성격을 클라이언트에 알리는 힌트: 쓰기 작업이고, 기존 데이터를 지우지 않으며,
    # 같은 요청을 다시 보내면 이슈가 하나 더 생기고(idempotent 아님), 외부 서비스(GitHub)를 호출한다
    annotations=ToolAnnotations(
        title="GitHub 이슈 생성",
        readOnlyHint=False,
        destructiveHint=False,
        idempotentHint=False,
        openWorldHint=True,
    ),
)
def create_issue(
    owner: str,
    repo: str,
    title: str,
    body: str,
    labels: list[str] | None = None,
    assignees: list[str] | None = None,
) -> dict[str, Any]:
    """새 이슈를 만들고 번호·URL·실제 지정된 담당자를 돌려준다. 실패하면 "[코드] 메시지" ToolError."""
    # owner/repo는 에이전트가 넘긴 값(백엔드 요청의 프로젝트 저장소)이다.
    # 현재는 .env에 허용된 저장소일 때만 통과시키고, GitHub를 부르기 전에 확인한다
    github.check_allowed(owner, repo)
    # 요청마다 클라이언트를 열고 닫는다 (호출이 드물어 연결을 유지할 필요가 없음)
    with github.make_client() as client:
        return github.create_issue(client, owner, repo, title, body, labels, assignees)


@mcp.tool(
    description="GitHub 저장소의 최근 이슈 목록을 조회한다 (PR 제외). 허용된 저장소만 가능하다.",
    # 읽기 전용이고, 같은 요청을 반복해도 결과가 같은 도구라는 힌트
    annotations=ToolAnnotations(
        title="GitHub 이슈 목록 조회",
        readOnlyHint=True,
        destructiveHint=False,
        idempotentHint=True,
        openWorldHint=True,
    ),
)
def list_issues(owner: str, repo: str, state: str = "open", limit: int = 10) -> dict[str, Any]:
    """최근 이슈 목록을 {"issues": [...]}로 돌려준다. 에이전트의 MCP 연결 확인에 쓴다."""
    # 읽기 도구도 허용된 저장소만 볼 수 있게 같은 검사를 한다
    github.check_allowed(owner, repo)
    with github.make_client() as client:
        return {"issues": github.list_issues(client, owner, repo, state, limit)}


@mcp.tool(
    description=(
        "저장소 안에서 검색어가 들어간 이슈를 찾는다 (PR 제외). "
        "회의록에 나온 이슈 키(예: AUTH-112)나 기능 이름처럼 정확한 단어로 찾을 때 쓴다. "
        "표현이 달라도 같은 작업인지 찾을 때는 rag-mcp의 search_similar_issues를 쓴다. "
        "결과의 state(open/closed)와 closed_at으로 진행 중인 작업인지 끝난 작업인지 구분한다."
    ),
    # 읽기 전용이고, 같은 요청을 반복해도 결과가 같은 도구라는 힌트
    annotations=ToolAnnotations(
        title="GitHub 이슈 키워드 검색",
        readOnlyHint=True,
        destructiveHint=False,
        idempotentHint=True,
        openWorldHint=True,
    ),
)
def search_issues(
    owner: str, repo: str, query: str, state: Literal["open", "closed", "all"] = "all", limit: int = 5
) -> dict[str, Any]:
    """검색 결과를 {"issues": [{number, title, state, url, closed_at}]}로 돌려준다 (최대 20개)."""
    # 다른 도구와 같이 허용된 저장소만 검색한다
    github.check_allowed(owner, repo)
    with github.make_client() as client:
        return {"issues": github.search_issues(client, owner, repo, query, state, limit)}


if __name__ == "__main__":
    # 기본값은 로컬 전용(127.0.0.1). 다른 컨테이너에서 접속해야 하면 MCP_HOST=0.0.0.0
    host = os.environ.get("MCP_HOST", "127.0.0.1")
    port = int(os.environ.get("MCP_PORT", "8000"))
    mcp.run(transport="streamable-http", host=host, port=port)
