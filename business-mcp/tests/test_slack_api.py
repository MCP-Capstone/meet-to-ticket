"""integrations/slack/api.py 테스트. httpx.MockTransport로 실제 Slack 호출 없이 검증한다."""

import json

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from integrations.slack import api


@pytest.fixture
def slack_client(monkeypatch):
    """handler(request) -> httpx.Response 형태의 가짜 Slack 클라이언트를 만든다."""
    monkeypatch.setenv("SLACK_BOT_TOKEN", "test-token")

    def make(handler):
        return api.make_client(transport=httpx.MockTransport(handler))

    return make


def test_channel_name_is_resolved_and_bot_membership_is_confirmed(slack_client):
    calls = []

    def handler(request):
        calls.append(request.url.path)
        if request.url.path == "/api/conversations.list":
            return httpx.Response(200, json={"ok": True, "channels": [{"id": "C12345678", "name": "project-a"}]})
        assert request.url.path == "/api/conversations.info"
        assert request.url.params["channel"] == "C12345678"
        return httpx.Response(200, json={"ok": True, "channel": {"id": "C12345678", "name": "project-a", "is_member": True}})

    with slack_client(handler) as client:
        out = api.check_slack_channel(client, "#Project-A")

    assert out == {"status": "CONNECTED", "channel_id": "C12345678", "channel_name": "project-a", "is_member": True, "invite_guide": None}
    assert calls == ["/api/conversations.list", "/api/conversations.info"]


def test_channel_without_bot_returns_invitation_guide(slack_client):
    def handler(request):
        assert request.url.path == "/api/conversations.info"
        return httpx.Response(200, json={"ok": True, "channel": {"id": "C12345678", "name": "project-a", "is_member": False}})

    with slack_client(handler) as client:
        out = api.check_slack_channel(client, "C12345678")

    assert out["status"] == "NOT_CONNECTED"
    assert out["channel_id"] == "C12345678"
    assert out["is_member"] is False
    assert "초대" in out["invite_guide"]


def test_unknown_channel_is_a_connection_result_not_an_exception(slack_client):
    def handler(request):
        assert request.url.params["types"] in {"public_channel", "private_channel"}
        return httpx.Response(200, json={"ok": True, "channels": []})

    with slack_client(handler) as client:
        out = api.check_slack_channel(client, "missing-channel")

    assert out["status"] == "NOT_FOUND"
    assert out["channel_id"] is None


def test_public_channel_does_not_require_private_channel_scope(slack_client):
    calls = []

    def handler(request):
        calls.append(request.url.path)
        if request.url.path == "/api/conversations.list":
            assert request.url.params["types"] == "public_channel"
            return httpx.Response(200, json={"ok": True, "channels": [{"id": "C12345678", "name": "public-project"}]})
        return httpx.Response(200, json={"ok": True, "channel": {"id": "C12345678", "name": "public-project", "is_member": True}})

    with slack_client(handler) as client:
        assert api.check_slack_channel(client, "public-project")["status"] == "CONNECTED"

    assert calls == ["/api/conversations.list", "/api/conversations.info"]


def test_user_lookup_distinguishes_connected_and_missing_users(slack_client):
    def found(request):
        assert request.url.params["email"] == "member@example.com"
        return httpx.Response(200, json={"ok": True, "user": {"id": "U12345678", "profile": {"display_name": "윤경"}}})

    with slack_client(found) as client:
        assert api.lookup_slack_user(client, " MEMBER@example.com ") == {
            "status": "CONNECTED", "email": "member@example.com", "slack_user_id": "U12345678", "display_name": "윤경"
        }

    with slack_client(lambda request: httpx.Response(200, json={"ok": False, "error": "users_not_found"})) as client:
        assert api.lookup_slack_user(client, "missing@example.com") == {
            "status": "NOT_FOUND", "email": "missing@example.com", "slack_user_id": None, "display_name": None
        }


def test_post_message_replaces_only_exact_connected_mentions(slack_client):
    calls = []

    def handler(request):
        calls.append(request.url.path)
        if request.url.path == "/api/chat.postMessage":
            payload = json.loads(request.content)
            assert payload == {"channel": "C12345678", "text": "담당자 <@U12345678>, @홍길동님 @미연결에게 확인 바랍니다."}
            return httpx.Response(200, json={"ok": True, "ts": "1720000000.000100"})
        assert request.url.path == "/api/chat.getPermalink"
        assert request.url.params == httpx.QueryParams({"channel": "C12345678", "message_ts": "1720000000.000100"})
        return httpx.Response(200, json={"ok": True, "permalink": "https://workspace.slack.com/archives/C12345678/p1720000000000100"})

    with slack_client(handler) as client:
        out = api.post_message(
            client,
            "C12345678",
            "담당자 @홍길동, @홍길동님 @미연결에게 확인 바랍니다.",
            [{"name": "홍길동", "slack_user_id": "U12345678"}, {"name": "미연결", "slack_user_id": None}],
        )

    assert out == {
        "status": "posted", "channel_id": "C12345678", "timestamp": "1720000000.000100",
        "permalink": "https://workspace.slack.com/archives/C12345678/p1720000000000100", "mentioned_user_ids": ["U12345678"]
    }
    assert calls == ["/api/chat.postMessage", "/api/chat.getPermalink"]


@pytest.mark.parametrize(
    ("response", "code"),
    [
        (httpx.Response(200, json={"ok": False, "error": "missing_scope"}), api.SLACK_FORBIDDEN),
        (httpx.Response(200, json={"ok": False, "error": "invalid_auth"}), api.SLACK_AUTH),
        (httpx.Response(429, json={"ok": False, "error": "ratelimited"}), api.SLACK_RATE_LIMIT),
    ],
)
def test_slack_errors_are_converted(slack_client, response, code):
    with slack_client(lambda request: response) as client:
        with pytest.raises(ToolError, match=rf"^\[{code}\]"):
            api.lookup_slack_user(client, "member@example.com")


def test_post_message_rejects_invalid_values(slack_client):
    with slack_client(lambda request: httpx.Response(200, json={"ok": True})) as client:
        with pytest.raises(ToolError, match="channel_id"):
            api.post_message(client, "general", "hello")
        with pytest.raises(ToolError, match="text"):
            api.post_message(client, "C12345678", " ")
        with pytest.raises(ToolError, match="slack_user_id"):
            api.post_message(client, "C12345678", "@윤경", [{"name": "윤경", "slack_user_id": "bad"}])
