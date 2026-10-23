"""GitHub REST 래퍼.

MCP 도구(tools.py)가 이 모듈을 통해서만 GitHub를 호출한다. 이 모듈은 MCP 도구 등록을 모른다.
- 토큰: business-mcp/.env의 GITHUB_TOKEN 하나(서비스 계정)를 모든 저장소에 쓴다.
- 저장소: 백엔드가 프로젝트 정보로 넘긴 owner/repo를 에이전트가 도구 인자로 넣는다.
  GITHUB_ALLOWED_REPOS에 값이 있으면 그 저장소만 허용하고, 비어 있으면 검사하지 않는다 (로컬 테스트용 안전장치).
- 오류는 "[코드] 메시지" 형식의 ToolError로 바꿔 에이전트가 코드로 구분할 수 있게 한다.
- 테스트에서는 make_client(transport=httpx.MockTransport(...))로 실제 요청 없이 검증한다.
"""

from __future__ import annotations

import re
from typing import Any

import httpx
from mcp.server.mcpserver.exceptions import ToolError

from common import INVALID_ARGUMENT, env, make_http_client, tool_error

API_BASE = "https://api.github.com"
TITLE_MAX = 256  # GitHub 이슈 제목 최대 길이
BODY_MAX = 65000  # GitHub 본문 한도(65536자)보다 조금 작게 잡아 생략 안내 문구 자리를 남긴다

# 에러 태그 (에이전트가 대괄호 안의 코드를 읽는다)
REPOSITORY_NOT_ALLOWED = "REPOSITORY_NOT_ALLOWED"  # GITHUB_ALLOWED_REPOS에 없는 저장소
GITHUB_AUTH = "GITHUB_AUTH"  # 401/403: 토큰이 없거나 권한 부족
GITHUB_NOT_FOUND = "GITHUB_NOT_FOUND"  # 404: 저장소가 없거나 토큰으로 볼 수 없음
GITHUB_VALIDATION = "GITHUB_VALIDATION"  # 422: GitHub가 요청 값을 거부
GITHUB_RATE_LIMIT = "GITHUB_RATE_LIMIT"  # 429 또는 남은 호출 수 0인 403
GITHUB_ERROR = "GITHUB_ERROR"  # 그 밖의 실패 (5xx 등)


def allowed_repos() -> set[str]:
    """GITHUB_ALLOWED_REPOS(쉼표 구분 owner/repo). 비어 있으면 빈 집합 = 검사하지 않음."""
    # GitHub는 owner/repo 대소문자를 구분하지 않으므로 소문자로 맞춰 비교한다
    return {item.strip().lower() for item in env("GITHUB_ALLOWED_REPOS").split(",") if item.strip()}


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
        "User-Agent": "meet-to-ticket-mcp",  # GitHub API는 User-Agent가 없으면 요청을 거부한다
    }
    token = env("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return make_http_client(API_BASE, headers, transport)


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


def request(client: httpx.Client, method: str, path: str, *, check: bool = True, **kwargs: Any) -> httpx.Response:
    """GitHub를 호출하고 실패 응답을 에러 태그로 바꾼다.

    check=False면 실패 응답도 그대로 돌려준다 (호출한 쪽이 응답을 보고 다시 시도할지 정할 때).
    연결 실패·시간 초과는 태그 없이 그대로 올라가고, 에이전트는 이를 "결과 모름"으로 처리한다.
    """
    resp = client.request(method, path, **kwargs)
    if check:
        raise_for_status(resp)
    return resp


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
    resp = request(
        client,
        "GET",
        f"/repos/{owner}/{repo}/issues",
        # GitHub 이슈 API는 PR도 함께 돌려주므로, PR을 빼고도 limit개가 남도록 넉넉히 받는다
        params={"state": state, "per_page": min(100, limit * 2), "sort": "created", "direction": "desc"},
    )
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
    resp = request(client, "GET", "/search/issues", params={"q": q, "per_page": limit})

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


MEMBERS_MAX = 100  # 한 번에 확인하는 멤버 수 상한 (호출 한도를 지키기 위해)
# 멤버별 저장소 권한 (프론트 계약의 repositoryPermission과 같은 값. NO_ACCOUNT는 백엔드가 정한다)
GRANTED = "GRANTED"  # 이 저장소 이슈의 담당자로 지정할 수 있음
DENIED = "DENIED"  # 지정할 수 없음 (저장소 권한 없음, 또는 없는 계정)
ERROR = "ERROR"  # 확인 실패 (권한 없음으로 보지 않는다)


def check_member_permissions(
    client: httpx.Client, owner: str, repo: str, github_names: list[str]
) -> list[dict[str, str]]:
    """멤버마다 이 저장소 이슈의 담당자로 지정할 수 있는지 확인한다.

    GitHub assignees API: 지정 가능하면 204, 아니면 404.
    저장소 자체를 볼 수 없으면 모든 멤버가 404가 되어 "권한 없음"으로 잘못 보이므로, 먼저 저장소를 조회하고
    실패하면 오류(GITHUB_NOT_FOUND 등)로 전체를 돌려준다. 멤버 한 명의 확인이 실패하면 그 멤버만 ERROR.
    """
    names: list[str] = []
    for name in github_names or []:
        name = (name or "").strip()
        if name and name.lower() not in {n.lower() for n in names}:
            names.append(name)
    if len(names) > MEMBERS_MAX:
        raise tool_error(INVALID_ARGUMENT, f"한 번에 확인할 수 있는 멤버는 {MEMBERS_MAX}명까지입니다")
    if not names:
        return []

    request(client, "GET", f"/repos/{owner}/{repo}")
    out = []
    for name in names:
        try:
            resp = request(client, "GET", f"/repos/{owner}/{repo}/assignees/{name}", check=False)
        except httpx.HTTPError:
            out.append({"github_name": name, "permission": ERROR})
            continue
        if resp.status_code == 204:
            permission = GRANTED
        elif resp.status_code == 404:
            permission = DENIED
        else:
            permission = ERROR
        out.append({"github_name": name, "permission": permission})
    return out


LABELS_PER_PAGE = 100  # GitHub가 허용하는 한 페이지 최대 개수


def list_labels(client: httpx.Client, owner: str, repo: str) -> list[dict[str, Any]]:
    """저장소에 있는 라벨 전체를 [{name, description}]로 돌려준다. Agent가 초안 라벨을 고를 때 쓴다."""
    out = []
    url: str | None = f"/repos/{owner}/{repo}/labels"
    params: dict[str, Any] | None = {"per_page": LABELS_PER_PAGE}
    while url:
        resp = request(client, "GET", url, params=params)
        out.extend({"name": item.get("name"), "description": item.get("description") or ""} for item in resp.json())
        # 다음 페이지 주소는 Link 헤더에 조건까지 들어 있어 params를 다시 넘기지 않는다
        url = resp.links.get("next", {}).get("url")
        params = None
    return out


def split_labels(
    client: httpx.Client, owner: str, repo: str, labels: list[str] | None
) -> tuple[list[str], list[str]]:
    """요청한 라벨을 저장소에 있는 것(저장소 표기로)과 없는 것으로 나눈다.

    push 권한이 있는 토큰으로 없는 라벨을 넘기면 GitHub가 라벨을 새로 만들 수 있어, 보내기 전에 미리 뺀다.
    GitHub 라벨 이름은 대소문자를 구분하지 않으므로 소문자로 비교한다.
    """
    wanted = [label.strip() for label in (labels or []) if label and label.strip()]
    if not wanted:
        return [], []
    existing = {item["name"].lower(): item["name"] for item in list_labels(client, owner, repo) if item["name"]}
    kept: list[str] = []
    dropped: list[str] = []
    for label in wanted:
        name = existing.get(label.lower())
        if name is None:
            if label not in dropped:
                dropped.append(label)
        elif name not in kept:
            kept.append(name)
    return kept, dropped


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

    저장소에 없는 라벨은 빼고 만들고 dropped_labels로 알려 준다.
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
    kept_labels, dropped_labels = split_labels(client, owner, repo, labels)
    if kept_labels:
        payload["labels"] = kept_labels
    if requested:
        payload["assignees"] = requested

    path = f"/repos/{owner}/{repo}/issues"
    resp = request(client, "POST", path, check=False, json=payload)
    # 담당자 때문에 이슈 자체가 안 만들어지는 것보다, 담당자 없이라도 만드는 쪽을 택한다
    if requested and _is_assignee_error(resp):
        payload.pop("assignees", None)
        resp = request(client, "POST", path, check=False, json=payload)
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
        "dropped_labels": dropped_labels,
    }


def _issue_number(number: int) -> int:
    """GitHub 이슈 번호를 API 호출 전에 검증한다."""
    # bool은 int의 하위 타입이지만 이슈 번호로는 의미가 없다.
    if isinstance(number, bool):
        raise tool_error(INVALID_ARGUMENT, "number는 1 이상의 이슈 번호여야 합니다")
    try:
        parsed = int(number)
    except (TypeError, ValueError):
        raise tool_error(INVALID_ARGUMENT, "number는 1 이상의 이슈 번호여야 합니다") from None
    if parsed < 1:
        raise tool_error(INVALID_ARGUMENT, "number는 1 이상의 이슈 번호여야 합니다")
    return parsed


def _required_text(value: str, name: str) -> str:
    """공백만 있는 쓰기 본문은 GitHub에 보내지 않는다."""
    text = (value or "").strip()
    if not text:
        raise tool_error(INVALID_ARGUMENT, f"{name}이 비어 있습니다")
    if len(text) > BODY_MAX:
        raise tool_error(INVALID_ARGUMENT, f"{name}은 {BODY_MAX}자 이하여야 합니다")
    return text


def add_comment(client: httpx.Client, owner: str, repo: str, number: int, body: str) -> dict[str, Any]:
    """기존 이슈에 댓글을 하나 추가한다. 재호출하면 댓글도 하나 더 생긴다."""
    number = _issue_number(number)
    body = _required_text(body, "body")
    resp = request(client, "POST", f"/repos/{owner}/{repo}/issues/{number}/comments", json={"body": body})
    data = resp.json()
    return {"status": "commented", "comment_id": data.get("id"), "url": data.get("html_url")}


def _issue_labels(data: dict[str, Any]) -> list[str]:
    """이슈 조회 응답의 라벨을 PATCH에 다시 넣을 수 있는 이름 목록으로 바꾼다."""
    return [
        label.get("name")
        for label in data.get("labels", []) or []
        if isinstance(label, dict) and label.get("name")
    ]


def _merge_labels(existing: list[str], added: list[str]) -> list[str]:
    """기존 라벨은 유지하고, 대소문자 중복 없이 새 라벨만 덧붙인다."""
    merged: list[str] = []
    for label in [*existing, *added]:
        if label and label.lower() not in {item.lower() for item in merged}:
            merged.append(label)
    return merged


def update_issue(
    client: httpx.Client,
    owner: str,
    repo: str,
    number: int,
    append_section: str,
    add_labels: list[str] | None = None,
    note: str | None = None,
) -> dict[str, Any]:
    """기존 이슈 본문 끝에 섹션을 추가하고, 필요하면 라벨과 갱신 알림 댓글을 더한다.

    제목·담당자·state는 바꾸지 않는다. 본문 PATCH가 성공한 뒤 댓글만 실패하면 본문 갱신은
    유지하고 comment_error를 돌려주므로, 호출자가 이슈 갱신 자체를 실패로 잘못 처리하지 않는다.
    """
    number = _issue_number(number)
    section = _required_text(append_section, "append_section")
    path = f"/repos/{owner}/{repo}/issues/{number}"

    # PATCH의 body는 교체 방식이다. 먼저 현재 본문을 읽어, 기존 내용을 잃지 않고 끝에만 붙인다.
    current = request(client, "GET", path).json()
    if "pull_request" in current:
        raise tool_error(INVALID_ARGUMENT, "PR에는 update_issue를 사용할 수 없습니다")
    previous_body = (current.get("body") or "").rstrip()
    body = f"{previous_body}\n\n{section}" if previous_body else section
    # 생성 때처럼 자르면 기존 이슈의 끝이 조용히 사라질 수 있으므로 갱신은 안전하게 거부한다.
    if len(body) > BODY_MAX:
        raise tool_error(INVALID_ARGUMENT, f"기존 본문과 append_section 합계는 {BODY_MAX}자 이하여야 합니다")

    kept_labels, dropped_labels = split_labels(client, owner, repo, add_labels)
    payload: dict[str, Any] = {"body": body}
    if kept_labels:
        payload["labels"] = _merge_labels(_issue_labels(current), kept_labels)

    data = request(client, "PATCH", path, json=payload).json()
    out: dict[str, Any] = {
        "status": "updated",
        "number": data.get("number", number),
        "url": data.get("html_url"),
        "dropped_labels": dropped_labels,
        "comment_id": None,
        "comment_url": None,
        "comment_error": None,
    }
    if note is None or not note.strip():
        return out

    try:
        comment = add_comment(client, owner, repo, number, note)
    except (ToolError, httpx.HTTPError) as exc:
        # 본문과 라벨은 이미 변경됐다. 댓글 실패가 이 성공을 감추지 않게 결과에만 남긴다.
        out["comment_error"] = str(exc)
        return out
    out["comment_id"] = comment["comment_id"]
    out["comment_url"] = comment["url"]
    return out
