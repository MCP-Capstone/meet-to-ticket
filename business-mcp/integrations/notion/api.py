"""Notion REST 래퍼.

토큰은 business-mcp/.env의 NOTION_TOKEN 하나만 쓴다. 프로젝트별 기획 페이지는
백엔드가 Agent에 넘기고, Agent가 도구 인자로 전달한다. 토큰과 페이지 범위는 모델에 주지 않는다.
"""

from __future__ import annotations

import re
import time
from typing import Any

import httpx

from common import INVALID_ARGUMENT, env, make_http_client, request_with_outcome, tool_error
from mcp.server.mcpserver.exceptions import ToolError
from integrations.notion import blocks

API_BASE = "https://api.notion.com/v1"
# 페이지/블록 API 호환성을 보장하는 안정 버전. 새 기능이 필요할 때만 명시적으로 올린다.
NOTION_VERSION = "2022-06-28"
PAGE_ID = re.compile(r"(?i)([0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}|[0-9a-f]{32})")
MAX_READ_CHARS = 12_000
DEFAULT_READ_CHARS = 4_000
TICKET_FOLDER_TITLE = "회의 후속 티켓"

NOTION_CONFIG_MISSING = "NOTION_CONFIG_MISSING"
NOTION_AUTH = "NOTION_AUTH"
NOTION_FORBIDDEN = "NOTION_FORBIDDEN"
NOTION_NOT_FOUND = "NOTION_NOT_FOUND"
NOTION_VALIDATION = "NOTION_VALIDATION"
NOTION_RATE_LIMIT = "NOTION_RATE_LIMIT"
NOTION_ERROR = "NOTION_ERROR"

# 429 응답의 Retry-After를 따르되 도구 호출이 오래 묶이지 않도록 대기와 횟수를 제한한다.
RATE_LIMIT_ATTEMPTS = 3
RETRY_AFTER_CAP_SECONDS = 5.0
RETRY_AFTER_DEFAULT_SECONDS = 1.0
_sleep = time.sleep


def normalize_page_id(page_url_or_id: str) -> str:
    """Notion URL 또는 UUID에서 API에 쓸 UUID를 뽑는다."""
    matched = PAGE_ID.search((page_url_or_id or "").strip())
    if not matched:
        raise tool_error(INVALID_ARGUMENT, "Notion 페이지 URL 또는 페이지 ID 형식을 확인해 주세요")
    compact = matched.group(1).replace("-", "").lower()
    return f"{compact[:8]}-{compact[8:12]}-{compact[12:16]}-{compact[16:20]}-{compact[20:]}"


def make_client(transport: httpx.BaseTransport | None = None) -> httpx.Client:
    """Notion REST 클라이언트. 토큰 부재는 설정 오류로 빠르게 알려 준다."""
    token = env("NOTION_TOKEN")
    if not token:
        raise tool_error(NOTION_CONFIG_MISSING, "business-mcp/.env에 NOTION_TOKEN을 설정해야 합니다")
    return make_http_client(
        API_BASE,
        {"Authorization": f"Bearer {token}", "Notion-Version": NOTION_VERSION, "Accept": "application/json"},
        transport,
    )


def _message(resp: httpx.Response) -> str:
    try:
        data = resp.json()
    except ValueError:
        return resp.text[:300] or resp.reason_phrase
    return data.get("message") or data.get("code") or resp.reason_phrase if isinstance(data, dict) else resp.reason_phrase


def raise_for_status(resp: httpx.Response) -> None:
    if resp.is_success:
        return
    message = f"Notion API {resp.status_code}: {_message(resp)}"
    if resp.status_code == 400:
        raise tool_error(NOTION_VALIDATION, message)
    if resp.status_code == 401:
        raise tool_error(NOTION_AUTH, message)
    if resp.status_code == 403:
        raise tool_error(NOTION_FORBIDDEN, message)
    if resp.status_code == 404:
        raise tool_error(NOTION_NOT_FOUND, message)
    if resp.status_code == 429:
        raise tool_error(NOTION_RATE_LIMIT, message)
    raise tool_error(NOTION_ERROR, message)


def request(client: httpx.Client, method: str, path: str, **kwargs: Any) -> httpx.Response:
    resp = request_with_outcome(client, method, path, **kwargs)
    raise_for_status(resp)
    return resp


def _title(page: dict[str, Any]) -> str:
    """페이지 properties 중 title 타입 필드를 찾아 문자열로 만든다."""
    for value in (page.get("properties") or {}).values():
        if isinstance(value, dict) and value.get("type") == "title":
            return "".join(item.get("plain_text", "") for item in value.get("title") or [])
    return ""


def _page_summary(page: dict[str, Any]) -> dict[str, str]:
    return {"page_id": page.get("id"), "title": _title(page), "url": page.get("url")}


def check_notion_page(client: httpx.Client, page_url_or_id: str) -> dict[str, str]:
    """페이지가 토큰에 공유되었는지 확인하고, 정규 페이지 ID/제목/URL을 돌려준다."""
    page_id = normalize_page_id(page_url_or_id)
    return _page_summary(request(client, "GET", f"/pages/{page_id}").json())


def _children(client: httpx.Client, page_id: str) -> list[dict[str, Any]]:
    """한 페이지의 최상위 블록 전체를 페이지네이션해 받는다."""
    out: list[dict[str, Any]] = []
    cursor: str | None = None
    while True:
        params: dict[str, Any] = {"page_size": 100}
        if cursor:
            params["start_cursor"] = cursor
        data = request(client, "GET", f"/blocks/{page_id}/children", params=params).json()
        out.extend(data.get("results") or [])
        if not data.get("has_more"):
            return out
        cursor = data.get("next_cursor")
        if not cursor:
            return out


def list_pages(client: httpx.Client, root_page_url_or_id: str) -> list[dict[str, str]]:
    """기획 상위 페이지 바로 아래 문서 목록을 돌려준다. 자동 생성 티켓 폴더는 맥락 문서에서 뺀다."""
    root_id = normalize_page_id(root_page_url_or_id)
    # 먼저 root 자체를 읽어 접근 불가를 자식 목록의 빈 결과로 오인하지 않는다.
    request(client, "GET", f"/pages/{root_id}")
    pages = []
    for block in _children(client, root_id):
        if block.get("type") != "child_page":
            continue
        title = (block.get("child_page") or {}).get("title", "")
        if title == TICKET_FOLDER_TITLE:
            continue
        page_id = block.get("id")
        if page_id:
            pages.append({"page_id": page_id, "title": title})
    return pages


def read_page(client: httpx.Client, page_url_or_id: str, max_chars: int = DEFAULT_READ_CHARS) -> dict[str, Any]:
    """Notion 페이지를 Markdown으로 읽는다. max_chars만 돌려주고 잘린 여부를 명시한다."""
    try:
        max_chars = int(max_chars)
    except (TypeError, ValueError):
        raise tool_error(INVALID_ARGUMENT, "max_chars는 숫자여야 합니다") from None
    if not 1 <= max_chars <= MAX_READ_CHARS:
        raise tool_error(INVALID_ARGUMENT, f"max_chars는 1~{MAX_READ_CHARS} 사이여야 합니다")
    page_id = normalize_page_id(page_url_or_id)
    page = request(client, "GET", f"/pages/{page_id}").json()
    markdown = blocks.blocks_to_markdown(_children(client, page_id))
    return {**_page_summary(page), "markdown": markdown[:max_chars], "truncated": len(markdown) > max_chars}


def _create_page(client: httpx.Client, parent_page_id: str, title: str, children: list[dict[str, Any]]) -> dict[str, Any]:
    return request(
        client,
        "POST",
        "/pages",
        json={
            "parent": {"type": "page_id", "page_id": parent_page_id},
            "properties": {"title": {"title": [{"type": "text", "text": {"content": title}}]}},
            "children": children,
        },
    ).json()


def _ticket_folder(client: httpx.Client, root_page_id: str) -> str:
    for block in _children(client, root_page_id):
        if block.get("type") == "child_page" and (block.get("child_page") or {}).get("title") == TICKET_FOLDER_TITLE:
            return block["id"]
    return _create_page(client, root_page_id, TICKET_FOLDER_TITLE, []).get("id")


def create_page(client: httpx.Client, root_page_url_or_id: str, title: str, markdown: str) -> dict[str, str]:
    """기획 상위 페이지 아래 '회의 후속 티켓' 폴더에 Markdown 티켓 페이지를 만든다."""
    title = (title or "").strip()
    if not title:
        raise tool_error(INVALID_ARGUMENT, "title이 비어 있습니다")
    if len(title) > 2000:
        raise tool_error(INVALID_ARGUMENT, "title은 2000자 이하여야 합니다")
    root_id = normalize_page_id(root_page_url_or_id)
    request(client, "GET", f"/pages/{root_id}")
    folder_id = _ticket_folder(client, root_id)
    page_blocks = blocks.markdown_to_blocks(markdown)
    groups = blocks.batches(page_blocks)
    page = _create_page(client, folder_id, title, groups[0] if groups else [])
    # 페이지 생성 API는 children 100개까지만 받는다. 남은 본문은 생성한 페이지에 이어 붙인다.
    for group in groups[1:]:
        _append_or_discard(client, page, group)
    return {"status": "created", "page_id": page.get("id"), "url": page.get("url"), "folder_page_id": folder_id}


def _retry_after(resp: httpx.Response) -> float:
    """Retry-After(초)를 읽어 0 이상 상한 이하로 자른다. 없거나 숫자가 아니면 기본값을 쓴다."""
    try:
        seconds = float(resp.headers.get("Retry-After", RETRY_AFTER_DEFAULT_SECONDS))
    except ValueError:
        seconds = RETRY_AFTER_DEFAULT_SECONDS
    return min(max(seconds, 0.0), RETRY_AFTER_CAP_SECONDS)


def _archive_page(client: httpx.Client, page_id: str) -> None:
    """페이지를 보관 처리한다. 429면 Retry-After만큼 기다린 뒤 한 번 더 시도한다."""
    path = f"/pages/{page_id}"
    resp = request_with_outcome(client, "PATCH", path, json={"archived": True})
    if resp.status_code == 429:
        _sleep(_retry_after(resp))
        resp = request_with_outcome(client, "PATCH", path, json={"archived": True})
    raise_for_status(resp)


def _append_or_discard(client: httpx.Client, page: dict[str, Any], group: list[dict[str, Any]]) -> None:
    """만든 페이지에 남은 본문을 붙인다. 붙이지 못하면 본문이 잘린 페이지를 발행됨으로 남기지 않는다.

    Notion 속도 제한(429)은 통합 단위로 걸리므로 곧바로 보관 처리하면 그 요청도 429가 나기 쉽다.
    그래서 429는 Retry-After(상한 있음)만큼 기다렸다가 본문 추가를 제한된 횟수만큼 다시 시도한다.
    Notion이 4xx로 확실히 거절했거나(429 재시도 소진 포함) 요청 전에 연결이 끊겼으면 만든 페이지를 보관(archive) 처리하고
    원래 오류로 실패시킨다. 페이지가 남지 않으므로 다시 시도해도 중복되지 않는다. 시간 초과, 5xx, 요청 중 연결 끊김처럼
    반영됐는지 모르거나 보관 처리에 실패하면 페이지가 남아 있으므로 RESULT_UNKNOWN으로 확인 대상으로 남긴다.
    """
    url = page.get("url", "")
    unknown = f"Notion 페이지 생성 후 본문 추가를 완료하지 못했습니다. {url} 확인 필요"
    for attempt in range(RATE_LIMIT_ATTEMPTS):
        try:
            resp = request_with_outcome(client, "PATCH", f"/blocks/{page['id']}/children", json={"children": group})
        except ToolError as exc:
            if not str(exc).startswith("[MCP_UNAVAILABLE]"):
                raise tool_error("RESULT_UNKNOWN", f"{unknown}: {exc}") from exc
            definite = exc
            break
        if resp.is_success:
            return
        if resp.status_code == 429 and attempt < RATE_LIMIT_ATTEMPTS - 1:
            _sleep(_retry_after(resp))
            continue
        try:
            raise_for_status(resp)
        except ToolError as exc:
            if resp.status_code >= 500:
                raise tool_error("RESULT_UNKNOWN", f"{unknown}: {exc}") from exc
            definite = exc
        break
    try:
        _archive_page(client, page["id"])
    except (ToolError, httpx.HTTPError) as exc:
        raise tool_error("RESULT_UNKNOWN", f"Notion 페이지 생성 후 본문 추가가 거절됐고({definite}) 본문이 잘린 페이지를 보관 처리하지 못했습니다. {url} 확인 필요: {exc}") from exc
    raise ToolError(f"{definite} (본문이 잘린 페이지 {url}는 보관 처리했습니다)") from definite
