"""GitHub 이슈 읽기 (색인용, 읽기 전용).

business-mcp/integrations/github/api.py와 같은 방식(허용 저장소 검사, 상태 코드별 오류 태그)을 따른다.
토큰과 허용 저장소는 config.load_settings()로 읽는다. GITHUB_ALLOWED_REPOS가 비어 있으면 저장소를 검사하지 않는다.
두 서버는 가상환경이 따로라 코드를 공유하지 않고, 이 서버에 필요한 읽기 기능만 둔다.
테스트에서는 make_client(transport=httpx.MockTransport(...))로 실제 요청 없이 검증한다.
"""

from __future__ import annotations

from typing import Any, Iterator

import httpx

from config import load_settings
from errors import (
    GITHUB_AUTH,
    GITHUB_ERROR,
    GITHUB_NOT_FOUND,
    GITHUB_RATE_LIMIT,
    INVALID_ARGUMENT,
    REPOSITORY_NOT_ALLOWED,
    tool_error,
)

API_BASE = "https://api.github.com"
PER_PAGE = 100  # GitHub가 허용하는 한 페이지 최대 개수


def allowed_repos() -> set[str]:
    """GITHUB_ALLOWED_REPOS(쉼표 구분 owner/repo). 비어 있으면 빈 집합 = 검사하지 않음."""
    raw = load_settings().github_allowed_repos
    # GitHub는 owner/repo 대소문자를 구분하지 않으므로 소문자로 맞춰 비교한다
    return {item.strip().lower() for item in raw.split(",") if item.strip()}


def check_allowed(owner: str, repo: str) -> None:
    """저장소 값을 확인하고, 허용 목록이 있으면 그 안에 있는지 본다. GitHub를 호출하기 전에 부른다."""
    if not owner.strip() or not repo.strip():
        raise tool_error(INVALID_ARGUMENT, "owner와 repo를 모두 입력해야 합니다")
    allowed = allowed_repos()
    if allowed and f"{owner}/{repo}".strip().lower() not in allowed:
        raise tool_error(REPOSITORY_NOT_ALLOWED, f"{owner}/{repo}는 허용되지 않은 저장소입니다")


def make_client(transport: httpx.BaseTransport | None = None) -> httpx.Client:
    """GitHub REST용 httpx 클라이언트. transport는 테스트에서 MockTransport를 넣을 때만 쓴다."""
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "meet-to-ticket-rag-mcp",  # GitHub API는 User-Agent가 없으면 요청을 거부한다
    }
    token = load_settings().github_token
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return httpx.Client(base_url=API_BASE, headers=headers, timeout=20.0, transport=transport)


def raise_for_status(resp: httpx.Response) -> None:
    """실패 응답을 상태 코드에 맞는 에러 태그의 ToolError로 바꿔 던진다."""
    if resp.is_success:
        return
    status = resp.status_code
    try:
        detail = resp.json().get("message", "")
    except ValueError:
        detail = resp.text[:300]
    msg = f"GitHub API {status}: {detail or resp.reason_phrase}"
    # 호출 한도 초과는 403으로도 오므로 인증 오류보다 먼저 확인한다
    if status == 429 or (status == 403 and resp.headers.get("x-ratelimit-remaining") == "0"):
        raise tool_error(GITHUB_RATE_LIMIT, msg)
    if status in (401, 403):
        raise tool_error(GITHUB_AUTH, msg)
    if status == 404:
        raise tool_error(GITHUB_NOT_FOUND, msg)
    raise tool_error(GITHUB_ERROR, msg)


def to_issue(item: dict[str, Any]) -> dict[str, Any]:
    """GitHub 응답에서 색인에 필요한 필드만 남긴다."""
    return {
        "number": item.get("number"),
        "title": item.get("title") or "",
        "body": item.get("body") or "",
        "state": item.get("state") or "",
        "url": item.get("html_url") or "",
        "updated_at": item.get("updated_at") or "",
        "closed_at": item.get("closed_at") or "",
    }


def iter_issues(client: httpx.Client, owner: str, repo: str, since: str | None = None) -> Iterator[dict[str, Any]]:
    """저장소의 이슈를 모두 돌려준다 (열린·닫힌 이슈, PR 제외).

    since(ISO 8601)를 주면 그 시각 이후에 바뀐 이슈만 받는다. 바뀐 이슈만 다시 색인할 때 쓴다.
    """
    params: dict[str, Any] | None = {"state": "all", "per_page": PER_PAGE, "sort": "updated", "direction": "asc"}
    if since:
        params["since"] = since
    url: str | None = f"/repos/{owner}/{repo}/issues"
    while url:
        resp = client.get(url, params=params)
        raise_for_status(resp)
        for item in resp.json():
            # GitHub 이슈 API는 PR도 함께 돌려준다. PR에는 pull_request 키가 있다
            if "pull_request" in item:
                continue
            yield to_issue(item)
        # 다음 페이지 주소는 Link 헤더에 있고, 조건(state, since, page 등)이 이미 들어 있다.
        # params=None으로 넘겨야 그 조건이 유지된다 (빈 dict를 넘기면 httpx가 조건을 지워 첫 페이지를 반복해서 받음)
        url = resp.links.get("next", {}).get("url")
        params = None


def get_issue(client: httpx.Client, owner: str, repo: str, number: int) -> dict[str, Any]:
    """이슈 한 건. PR 번호면 GITHUB_NOT_FOUND로 본다."""
    resp = client.get(f"/repos/{owner}/{repo}/issues/{number}")
    raise_for_status(resp)
    item = resp.json()
    if "pull_request" in item:
        raise tool_error(GITHUB_NOT_FOUND, f"#{number}은 이슈가 아니라 PR입니다")
    return to_issue(item)
