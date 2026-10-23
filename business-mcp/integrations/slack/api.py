"""Slack Web API 래퍼.

토큰은 business-mcp/.env의 SLACK_BOT_TOKEN 하나만 사용한다. 프로젝트별 채널과
멤버 이메일은 Backend가 Agent를 거쳐 도구 인자로 넘긴다. 이 모듈은 MCP 등록을 모른다.
"""

from __future__ import annotations

import re
from typing import Any

import httpx

from common import INVALID_ARGUMENT, env, make_http_client, tool_error

API_BASE = "https://slack.com/api"
CHANNEL_ID = re.compile(r"^[CG][A-Z0-9]{8,}$")
USER_ID = re.compile(r"^[UW][A-Z0-9]{8,}$")
MESSAGE_MAX = 40_000

SLACK_CONFIG_MISSING = "SLACK_CONFIG_MISSING"
SLACK_AUTH = "SLACK_AUTH"
SLACK_FORBIDDEN = "SLACK_FORBIDDEN"
SLACK_NOT_FOUND = "SLACK_NOT_FOUND"
SLACK_VALIDATION = "SLACK_VALIDATION"
SLACK_RATE_LIMIT = "SLACK_RATE_LIMIT"
SLACK_ERROR = "SLACK_ERROR"


def make_client(transport: httpx.BaseTransport | None = None) -> httpx.Client:
    """Slack Web API 클라이언트. 토큰 부재는 호출 전에 분명히 알린다."""
    token = env("SLACK_BOT_TOKEN")
    if not token:
        raise tool_error(SLACK_CONFIG_MISSING, "business-mcp/.env에 SLACK_BOT_TOKEN을 설정해야 합니다")
    return make_http_client(API_BASE, {"Authorization": f"Bearer {token}"}, transport)


def _message(resp: httpx.Response) -> str:
    try:
        data = resp.json()
    except ValueError:
        return resp.text[:300] or resp.reason_phrase
    if isinstance(data, dict):
        return data.get("error") or data.get("message") or resp.reason_phrase
    return resp.reason_phrase


def _raise_http_error(resp: httpx.Response) -> None:
    if resp.is_success:
        return
    message = f"Slack API {resp.status_code}: {_message(resp)}"
    if resp.status_code == 400:
        raise tool_error(SLACK_VALIDATION, message)
    if resp.status_code == 401:
        raise tool_error(SLACK_AUTH, message)
    if resp.status_code == 403:
        raise tool_error(SLACK_FORBIDDEN, message)
    if resp.status_code == 404:
        raise tool_error(SLACK_NOT_FOUND, message)
    if resp.status_code == 429:
        raise tool_error(SLACK_RATE_LIMIT, message)
    raise tool_error(SLACK_ERROR, message)


def _raise_api_error(data: dict[str, Any]) -> None:
    error = data.get("error") or "unknown_error"
    message = f"Slack API: {error}"
    if error in {"not_authed", "invalid_auth", "token_revoked", "account_inactive"}:
        raise tool_error(SLACK_AUTH, message)
    if error in {"missing_scope", "not_in_channel", "restricted_action", "method_not_supported_for_channel_type"}:
        raise tool_error(SLACK_FORBIDDEN, message)
    if error in {"channel_not_found", "user_not_found"}:
        raise tool_error(SLACK_NOT_FOUND, message)
    if error in {"ratelimited", "rate_limited"}:
        raise tool_error(SLACK_RATE_LIMIT, message)
    if error in {"invalid_arguments", "invalid_arg_name", "invalid_array_arg", "no_text", "msg_too_long"}:
        raise tool_error(SLACK_VALIDATION, message)
    raise tool_error(SLACK_ERROR, message)


def raw_request(client: httpx.Client, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
    """HTTP 상태만 확인하고 Slack JSON 응답을 돌려준다.

    일부 API 오류(users_not_found, channel_not_found)는 호출자가 연결 상태 결과로 바꿔야 하므로
    이 함수를 별도로 둔다.
    """
    resp = client.request(method, path, **kwargs)
    _raise_http_error(resp)
    try:
        data = resp.json()
    except ValueError as error:
        raise tool_error(SLACK_ERROR, "Slack API가 JSON이 아닌 응답을 반환했습니다") from error
    if not isinstance(data, dict):
        raise tool_error(SLACK_ERROR, "Slack API 응답 형식이 올바르지 않습니다")
    return data


def request(client: httpx.Client, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
    """성공(ok=true) 응답만 돌려준다. 연결 실패·시간 초과는 원문 예외로 올린다."""
    data = raw_request(client, method, path, **kwargs)
    if not data.get("ok"):
        _raise_api_error(data)
    return data


def _normalized_channel_name(channel: str) -> str:
    return (channel or "").strip().lstrip("#").lower()


def _channel_by_name(client: httpx.Client, channel_name: str) -> dict[str, Any] | None:
    """봇이 볼 수 있는 공개·비공개 채널을 페이지 전체에서 이름으로 찾는다."""
    cursor: str | None = None
    while True:
        params: dict[str, Any] = {
            "exclude_archived": "true",
            "limit": 200,
            "types": "public_channel,private_channel",
        }
        if cursor:
            params["cursor"] = cursor
        data = request(client, "GET", "/conversations.list", params=params)
        for item in data.get("channels") or []:
            if _normalized_channel_name(item.get("name", "")) == channel_name:
                return item
        metadata = data.get("response_metadata") or {}
        cursor = metadata.get("next_cursor")
        if not cursor:
            return None


def _channel_details(client: httpx.Client, channel_id: str) -> dict[str, Any] | None:
    """채널 정보를 읽고, 존재하지 않는 채널은 None으로 바꾼다."""
    data = raw_request(client, "GET", "/conversations.info", params={"channel": channel_id})
    if not data.get("ok"):
        if data.get("error") == "channel_not_found":
            return None
        _raise_api_error(data)
    channel = data.get("channel")
    if not isinstance(channel, dict):
        raise tool_error(SLACK_ERROR, "Slack API 응답에 channel 정보가 없습니다")
    return channel


def check_slack_channel(client: httpx.Client, channel: str) -> dict[str, Any]:
    """채널 이름 또는 ID를 ID로 정규화하고, 봇이 채널 구성원인지 확인한다."""
    value = (channel or "").strip()
    if not value:
        raise tool_error(INVALID_ARGUMENT, "channel이 비어 있습니다")

    if CHANNEL_ID.fullmatch(value.upper()):
        details = _channel_details(client, value.upper())
    else:
        name = _normalized_channel_name(value)
        if not name:
            raise tool_error(INVALID_ARGUMENT, "channel이 비어 있습니다")
        listed = _channel_by_name(client, name)
        details = _channel_details(client, listed["id"]) if listed and listed.get("id") else None

    if details is None:
        return {
            "status": "NOT_FOUND",
            "channel_id": None,
            "channel_name": None,
            "is_member": False,
            "invite_guide": "채널 이름 또는 ID를 확인하고 Slack에서 봇을 채널에 초대해 주세요.",
        }

    is_member = bool(details.get("is_member"))
    return {
        "status": "CONNECTED" if is_member else "NOT_CONNECTED",
        "channel_id": details.get("id"),
        "channel_name": details.get("name"),
        "is_member": is_member,
        "invite_guide": None if is_member else "Slack 채널에서 봇을 초대한 뒤 다시 확인해 주세요.",
    }


def lookup_slack_user(client: httpx.Client, email: str) -> dict[str, Any]:
    """이메일로 Slack 사용자를 찾는다. 미연결·탈퇴 사용자는 오류가 아닌 NOT_FOUND 결과다."""
    email = (email or "").strip().lower()
    if not email:
        raise tool_error(INVALID_ARGUMENT, "email이 비어 있습니다")
    data = raw_request(client, "GET", "/users.lookupByEmail", params={"email": email})
    if not data.get("ok"):
        if data.get("error") in {"users_not_found", "user_not_found"}:
            return {"status": "NOT_FOUND", "email": email, "slack_user_id": None, "display_name": None}
        _raise_api_error(data)
    user = data.get("user") or {}
    if user.get("deleted") or not user.get("id"):
        return {"status": "NOT_FOUND", "email": email, "slack_user_id": None, "display_name": None}
    profile = user.get("profile") or {}
    return {
        "status": "CONNECTED",
        "email": email,
        "slack_user_id": user.get("id"),
        "display_name": profile.get("display_name") or profile.get("real_name") or user.get("real_name") or user.get("name"),
    }


_MENTION_END = r"(?=$|[\s.,!?…:;\)\]\}>/\"'])"


def render_mentions(text: str, mentions: list[dict[str, Any]] | None) -> tuple[str, list[str]]:
    """본문의 정확한 @이름만 Slack 사용자 멘션으로 바꾼다.

    @홍길동님·@홍길동A처럼 이름 뒤에 문자가 이어지는 경우는 치환하지 않는다. slack_user_id가
    없는 미연결 멤버도 원문 이름을 그대로 남긴다.
    """
    rendered = text
    mentioned: list[str] = []
    valid: list[tuple[str, str]] = []
    for mention in mentions or []:
        if not isinstance(mention, dict):
            raise tool_error(INVALID_ARGUMENT, "mentions의 각 항목은 name과 slack_user_id를 가진 객체여야 합니다")
        name = str(mention.get("name") or "").strip().lstrip("@")
        user_id = str(mention.get("slack_user_id") or "").strip().upper()
        if not name or not user_id:
            continue
        if not USER_ID.fullmatch(user_id):
            raise tool_error(INVALID_ARGUMENT, "mentions의 slack_user_id 형식이 올바르지 않습니다")
        if (name, user_id) not in valid:
            valid.append((name, user_id))

    for name, user_id in sorted(valid, key=lambda item: len(item[0]), reverse=True):
        rendered, replacements = re.subn(rf"@{re.escape(name)}{_MENTION_END}", f"<@{user_id}>", rendered)
        if replacements and user_id not in mentioned:
            mentioned.append(user_id)
    return rendered, mentioned


def post_message(
    client: httpx.Client, channel_id: str, text: str, mentions: list[dict[str, Any]] | None = None
) -> dict[str, Any]:
    """프로젝트 채널에 메시지 한 개를 게시한다. 스레드는 쓰지 않으며 재호출하면 메시지가 추가된다."""
    channel_id = (channel_id or "").strip().upper()
    text = (text or "").strip()
    if not CHANNEL_ID.fullmatch(channel_id):
        raise tool_error(INVALID_ARGUMENT, "channel_id 형식이 올바르지 않습니다")
    if not text:
        raise tool_error(INVALID_ARGUMENT, "text가 비어 있습니다")
    if len(text) > MESSAGE_MAX:
        raise tool_error(INVALID_ARGUMENT, f"text는 {MESSAGE_MAX}자 이하여야 합니다")

    rendered, mentioned = render_mentions(text, mentions)
    posted = request(client, "POST", "/chat.postMessage", json={"channel": channel_id, "text": rendered})
    timestamp = posted.get("ts") or (posted.get("message") or {}).get("ts")
    if not timestamp:
        raise tool_error(SLACK_ERROR, "Slack API 응답에 메시지 시각이 없습니다")
    link = request(client, "GET", "/chat.getPermalink", params={"channel": channel_id, "message_ts": timestamp})
    return {
        "status": "posted",
        "channel_id": channel_id,
        "timestamp": timestamp,
        "permalink": link.get("permalink"),
        "mentioned_user_ids": mentioned,
    }
