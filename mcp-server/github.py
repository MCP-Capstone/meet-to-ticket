"""GitHub REST 래퍼.

MCP 도구(server.py)가 이 모듈을 통해서만 GitHub를 호출한다.
- 허용 저장소 검사: GITHUB_ALLOWED_REPOS(쉼표 구분 owner/repo)
- 오류는 "[코드] 메시지" 형식의 ToolError로 바꿔 에이전트가 코드로 구분할 수 있게 한다.
- 테스트에서는 make_client(transport=httpx.MockTransport(...))로 실제 요청 없이 검증한다.

저장소와 토큰을 정하는 방식
- 현재(1차): 토큰(GITHUB_TOKEN)과 쓸 수 있는 저장소 목록은 mcp-server/.env에서 읽는다.
  어느 저장소에 만들지(owner, repo)는 도구를 부를 때 인자로 받지만, .env에 허용된 저장소가 아니면 거부한다.
  즉 토큰 하나로, 미리 정해 둔 저장소에만 이슈를 만든다.
- 추후: 백엔드 API에서 프로젝트별 저장소와 연동 정보(토큰 등)를 받아,
  .env를 고치지 않고도 프로젝트가 연결한 저장소에 이슈를 만들 수 있게 바꾼다.
  그때는 allowed_repos()와 make_client()의 토큰 읽는 부분을 그 방식으로 교체하면 된다.
"""

from __future__ import annotations

import os
import re
from typing import Any

import httpx
from mcp.server.mcpserver.exceptions import ToolError

API_BASE = "https://api.github.com"
TITLE_MAX = 256  # GitHub 이슈 제목 최대 길이
BODY_MAX = 65000  # GitHub 본문 한도(65536자)보다 조금 작게 잡아 생략 안내 문구 자리를 남긴다

# 에러 태그 (에이전트의 call_tool이 대괄호 안의 코드를 읽는다)
REPOSITORY_NOT_ALLOWED = "REPOSITORY_NOT_ALLOWED"  # 허용 목록에 없는 저장소
INVALID_ARGUMENT = "INVALID_ARGUMENT"  # 도구 입력값 오류 (예: 빈 제목)
GITHUB_AUTH = "GITHUB_AUTH"  # 401/403: 토큰이 없거나 권한 부족
GITHUB_NOT_FOUND = "GITHUB_NOT_FOUND"  # 404: 저장소가 없거나 토큰으로 볼 수 없음
GITHUB_VALIDATION = "GITHUB_VALIDATION"  # 422: GitHub가 요청 값을 거부
GITHUB_RATE_LIMIT = "GITHUB_RATE_LIMIT"  # 429 또는 남은 호출 수 0인 403
GITHUB_ERROR = "GITHUB_ERROR"  # 그 밖의 실패 (5xx 등)


def tool_error(code: str, message: str) -> ToolError:
    """에이전트에 돌려줄 ToolError를 "[코드] 메시지" 형식으로 만든다."""
    return ToolError(f"[{code}] {message}")


def allowed_repos() -> set[str]:
    """허용 저장소 목록. GITHUB_ALLOWED_REPOS가 없으면 GITHUB_OWNER/GITHUB_REPO 하나만 허용한다.

    현재는 .env 값으로 고정한다. 추후 백엔드 API에서 프로젝트별 저장소를 받으면 이 함수를 교체한다.
    """
    raw = os.environ.get("GITHUB_ALLOWED_REPOS", "")
    # GitHub는 owner/repo 대소문자를 구분하지 않으므로 소문자로 맞춰 비교한다
    repos = {item.strip().lower() for item in raw.split(",") if item.strip()}
    owner = os.environ.get("GITHUB_OWNER", "").strip()
    repo = os.environ.get("GITHUB_REPO", "").strip()
    if owner and repo:
        repos.add(f"{owner}/{repo}".lower())
    return repos


def check_allowed(owner: str, repo: str) -> None:
    """허용되지 않은 저장소면 REPOSITORY_NOT_ALLOWED를 던진다. GitHub를 호출하기 전에 부른다."""
    full = f"{owner}/{repo}".strip().lower()
    if full not in allowed_repos():
        raise tool_error(REPOSITORY_NOT_ALLOWED, f"{owner}/{repo}는 허용되지 않은 저장소입니다")


def make_client(transport: httpx.BaseTransport | None = None) -> httpx.Client:
    """GitHub REST용 httpx 클라이언트. transport는 테스트에서 MockTransport를 넣을 때만 쓴다."""
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "meet-to-ticket-mcp",  # GitHub API는 User-Agent가 없으면 요청을 거부한다
    }
    # 현재는 .env의 GITHUB_TOKEN 하나를 모든 저장소에 쓴다 (추후 프로젝트별 토큰으로 교체 예정)
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return httpx.Client(base_url=API_BASE, headers=headers, timeout=20.0, transport=transport)


def _message(resp: httpx.Response) -> str:
    """실패 응답에서 사람이 읽을 메시지를 뽑는다. JSON이 아니면 본문 앞부분을 쓴다."""
    try:
        data = resp.json()
    except ValueError:
        return resp.text[:300] or resp.reason_phrase
    msg = data.get("message", "") if isinstance(data, dict) else ""
    # 422는 message가 "Validation Failed"뿐이라, 어느 필드가 문제인지 errors를 덧붙인다
    errors = data.get("errors") if isinstance(data, dict) else None
    if errors:
        msg = f"{msg} {errors}"
    return msg or resp.reason_phrase


def raise_for_status(resp: httpx.Response) -> None:
    """실패 응답을 상태 코드에 맞는 에러 태그의 ToolError로 바꿔 던진다."""
    if resp.is_success:
        return
    status = resp.status_code
    msg = f"GitHub API {status}: {_message(resp)}"
    # 호출 한도 초과는 403으로도 오므로 인증 오류보다 먼저 확인한다
    if status == 429 or (status == 403 and resp.headers.get("x-ratelimit-remaining") == "0"):
        raise tool_error(GITHUB_RATE_LIMIT, msg)
    if status in (401, 403):
        raise tool_error(GITHUB_AUTH, msg)
    if status == 404:
        raise tool_error(GITHUB_NOT_FOUND, msg)
    if status == 422:
        raise tool_error(GITHUB_VALIDATION, msg)
    raise tool_error(GITHUB_ERROR, msg)


def _is_assignee_error(resp: httpx.Response) -> bool:
    """422 응답이 담당자 지정 때문인지 확인한다 (저장소에 권한이 없는 사용자를 지정한 경우 등)."""
    if resp.status_code != 422:
        return False
    try:
        data = resp.json()
    except ValueError:
        return False
    for err in data.get("errors", []) or []:
        if isinstance(err, dict) and err.get("field") == "assignees":
            return True
    # errors에 field가 없는 응답도 있어서 문구로 한 번 더 확인한다
    return "assignee" in str(data).lower()


def list_issues(
    client: httpx.Client, owner: str, repo: str, state: str = "open", limit: int = 10
) -> list[dict[str, Any]]:
    """최근 이슈 목록을 최신순으로 돌려준다 (PR 제외). MCP 연결과 토큰 권한 확인용으로 쓴다."""
    if state not in ("open", "closed", "all"):
        raise tool_error(INVALID_ARGUMENT, "state는 open, closed, all 중 하나입니다")
    # 응답이 너무 커지지 않도록 1~50개로 제한한다
    limit = max(1, min(int(limit), 50))
    resp = client.get(
        f"/repos/{owner}/{repo}/issues",
        # GitHub 이슈 API는 PR도 함께 돌려주므로, PR을 빼고도 limit개가 남도록 넉넉히 받는다
        params={"state": state, "per_page": min(100, limit * 2), "sort": "created", "direction": "desc"},
    )
    raise_for_status(resp)
    out = []
    for item in resp.json():
        # PR에는 pull_request 키가 있다
        if "pull_request" in item:
            continue
        out.append({
            "number": item.get("number"),
            "title": item.get("title"),
            "state": item.get("state"),
            "url": item.get("html_url"),
            "assignees": [a.get("login") for a in item.get("assignees", []) or []],
        })
        if len(out) >= limit:
            break
    return out


SEARCH_LIMIT_MAX = 20  # 한 번에 돌려주는 최대 개수 (모델 입력이 길어지지 않게)
# 검색어에 섞여 들어오면 검색 범위를 바꾸는 한정자. 다른 저장소가 섞이지 않도록 지운 뒤 우리 범위를 붙인다
# (GitHub 검색은 repo: 한정자를 여러 개 주면 OR로 묶어서, 그대로 두면 다른 저장소까지 검색된다)
_SCOPE_QUALIFIER = re.compile(r"(?i)(?<!\S)-?(repo|org|user|owner|is|type|in|state):\S+")


def search_issues(
    client: httpx.Client, owner: str, repo: str, query: str, state: str = "all", limit: int = 5
) -> list[dict[str, Any]]:
    """저장소 안에서 검색어가 들어간 이슈를 찾는다 (PR 제외). 이슈 키(예: AUTH-112)나 정확한 단어를 찾을 때 쓴다.

    GitHub 검색 API(/search/issues)를 쓰며, 검색 범위(repo:, is:issue, state:)는 코드가 붙인다.
    """
    if state not in ("open", "closed", "all"):
        raise tool_error(INVALID_ARGUMENT, "state는 open, closed, all 중 하나입니다")
    words = _SCOPE_QUALIFIER.sub(" ", query or "").split()
    if not words:
        raise tool_error(INVALID_ARGUMENT, "query가 비어 있습니다")
    limit = max(1, min(int(limit), SEARCH_LIMIT_MAX))

    q = " ".join([*words, f"repo:{owner}/{repo}", "is:issue"] + ([f"state:{state}"] if state != "all" else []))
    resp = client.get("/search/issues", params={"q": q, "per_page": limit})
    raise_for_status(resp)

    repo_url = f"{API_BASE}/repos/{owner}/{repo}".lower()
    out = []
    for item in resp.json().get("items", []):
        # 범위를 코드가 붙였지만, 혹시 다른 저장소나 PR이 섞여 오면 한 번 더 걸러 낸다
        if "pull_request" in item or (item.get("repository_url") or "").lower() != repo_url:
            continue
        out.append({
            "number": item.get("number"),
            "title": item.get("title"),
            "state": item.get("state"),
            "url": item.get("html_url"),
            "closed_at": item.get("closed_at"),
        })
    return out[:limit]


def create_issue(
    client: httpx.Client,
    owner: str,
    repo: str,
    title: str,
    body: str,
    labels: list[str] | None = None,
    assignees: list[str] | None = None,
) -> dict[str, Any]:
    """이슈를 생성한다.

    담당자 지정이 거부되면(422) 담당자 없이 한 번 더 생성한다.
    GitHub가 권한 없는 담당자를 조용히 빼는 경우도 dropped_assignees로 알려 준다.
    """
    title = (title or "").strip()
    if not title:
        raise tool_error(INVALID_ARGUMENT, "title이 비어 있습니다")
    # 길이 초과로 422가 나서 발행이 실패하지 않도록 미리 자른다
    if len(title) > TITLE_MAX:
        title = title[: TITLE_MAX - 1] + "…"
    body = body or ""
    if len(body) > BODY_MAX:
        body = body[:BODY_MAX] + "\n\n(본문이 길어 일부 생략됨)"

    # 빈 문자열 담당자는 GitHub가 422로 거부하므로 미리 걸러 낸다
    requested = [a.strip() for a in (assignees or []) if a and a.strip()]
    payload: dict[str, Any] = {"title": title, "body": body}
    if labels:
        payload["labels"] = [label for label in labels if label]
    if requested:
        payload["assignees"] = requested

    path = f"/repos/{owner}/{repo}/issues"
    resp = client.post(path, json=payload)
    retried_without_assignees = False
    # 담당자 때문에 이슈 자체가 안 만들어지는 것보다, 담당자 없이라도 만드는 쪽을 택한다
    if requested and _is_assignee_error(resp):
        payload.pop("assignees", None)
        resp = client.post(path, json=payload)
        retried_without_assignees = True
    raise_for_status(resp)

    data = resp.json()
    # 요청한 담당자 중 실제로 지정되지 않은 사람을 골라 에이전트가 결과에 남길 수 있게 한다
    applied = [a.get("login") for a in data.get("assignees", []) or [] if isinstance(a, dict)]
    dropped = [a for a in requested if a.lower() not in {x.lower() for x in applied if x}]
    return {
        "status": "created",
        "number": data.get("number"),
        "url": data.get("html_url"),
        "assignees": applied,
        "dropped_assignees": dropped,
        "retried_without_assignees": retried_without_assignees,
    }
