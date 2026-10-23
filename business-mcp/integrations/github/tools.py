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


def add_comment(owner: str, repo: str, number: int, body: str) -> dict[str, Any]:
    """기존 이슈에 연결 댓글을 추가하고 댓글 ID·URL을 돌려준다. 실패하면 "[코드] 메시지" ToolError."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return api.add_comment(client, owner, repo, number, body)


def update_issue(
    owner: str,
    repo: str,
    number: int,
    append_section: str,
    add_labels: list[str] | None = None,
    note: str | None = None,
) -> dict[str, Any]:
    """이슈 본문 끝에 변경 섹션을 덧붙이고, 라벨 추가·갱신 댓글을 처리한다."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return api.update_issue(client, owner, repo, number, append_section, add_labels, note)


def list_labels(owner: str, repo: str) -> dict[str, Any]:
    """저장소 라벨 목록을 {"labels": [{name, description}]}로 돌려준다."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return {"labels": api.list_labels(client, owner, repo)}


def check_member_permissions(owner: str, repo: str, github_names: list[str]) -> dict[str, Any]:
    """멤버별 저장소 권한을 {"members": [{github_name, permission}]}로 돌려준다."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return {"members": api.check_member_permissions(client, owner, repo, github_names)}


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


def find_marker(owner: str, repo: str, number: int, marker: str) -> dict[str, Any]:
    """이슈 본문이나 댓글에 marker가 있는지 {found, where, url, truncated}로 돌려준다 (쓰기 없음)."""
    api.check_allowed(owner, repo)
    with api.make_client() as client:
        return api.find_marker(client, owner, repo, number, marker)


def register(mcp: MCPServer) -> None:
    """GitHub 도구를 서버에 등록한다."""
    mcp.tool(
        description=(
            "GitHub 저장소에 새 이슈를 만든다. "
            "저장소에 없는 라벨은 빼고 만들고 dropped_labels로, "
            "담당자 지정이 거부되면 담당자 없이 만들고 dropped_assignees로 알려 준다. "
            "다시 부르면 이슈가 하나 더 생긴다."
        ),
        annotations=titled(WRITE, "GitHub 이슈 생성"),
    )(create_issue)
    mcp.tool(
        description=(
            "기존 GitHub 이슈에 연결 댓글을 하나 추가한다. SAME으로 판단된 기존 이슈에 "
            "새 회의의 할 일을 연결할 때 쓴다. 다시 부르면 댓글이 하나 더 생긴다."
        ),
        annotations=titled(WRITE, "GitHub 기존 이슈 댓글 추가"),
    )(add_comment)
    mcp.tool(
        description=(
            "기존 GitHub 이슈 본문 끝에 변경 섹션을 덧붙인다. 제목·담당자·상태는 바꾸지 않고, "
            "라벨은 기존 것을 유지한 채 추가만 한다. note를 주면 갱신 알림 댓글도 남긴다. "
            "본문 갱신 뒤 댓글만 실패하면 갱신은 성공으로 돌려주고 comment_error에 실패 이유를 담는다."
        ),
        annotations=titled(WRITE, "GitHub 기존 이슈 갱신"),
    )(update_issue)
    mcp.tool(
        description="GitHub 저장소에 있는 라벨 목록(이름, 설명)을 조회한다. 이슈에 붙일 라벨을 이 안에서 고른다.",
        annotations=titled(READ, "GitHub 라벨 목록 조회"),
    )(list_labels)
    mcp.tool(
        description=(
            "멤버의 GitHub 계정마다 이 저장소 이슈의 담당자로 지정할 수 있는지 확인한다. "
            "permission은 GRANTED(가능), DENIED(불가), ERROR(확인 실패)이다. "
            "저장소 자체를 볼 수 없으면 멤버별 결과 없이 오류를 돌려준다."
        ),
        annotations=titled(READ, "GitHub 멤버 저장소 권한 확인"),
    )(check_member_permissions)
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
    mcp.tool(
        description=(
            "기존 GitHub 이슈의 본문이나 댓글에 marker 문자열이 이미 있는지 확인한다. 이슈는 바꾸지 않는다. "
            "결과를 모르는 발행을 다시 할 때 같은 연결 댓글이나 변경 섹션이 이미 반영됐는지 볼 때 쓴다. "
            "truncated가 true면 댓글이 많아 끝까지 보지 못한 것이므로 없다고 단정하지 않는다."
        ),
        annotations=titled(READ, "GitHub 이슈 표식 확인"),
    )(find_marker)
