"""POST /projects/check-connections: 프로젝트 저장용 MCP 결과를 독립적으로 반환한다."""

from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

import main


class FakeTool:
    def __init__(self, result=None, error: Exception | None = None):
        self.result, self.error, self.calls = result, error, []

    async def ainvoke(self, args):
        self.calls.append(args)
        if self.error:
            raise self.error
        return json.dumps(self.result)


@pytest.fixture
def client(settings):
    yield TestClient(main.app)
    main.app.state.overrides = {}


def test_연결과_Slack_사용자_ID를_확인해_camelCase로_돌려준다(client, settings):
    notion = FakeTool({"page_id": "p1", "url": "https://www.notion.so/normalized"})
    channel = FakeTool({"status": "CONNECTED", "channel_id": "C0123456789"})
    user = FakeTool({"status": "CONNECTED", "email": "member@example.com", "slack_user_id": "U0123456789"})
    main.app.state.overrides = {"settings": settings, "mcp_tools": {
        "check_notion_page": notion, "check_slack_channel": channel, "lookup_slack_user": user,
    }}

    resp = client.post("/projects/check-connections", json={
        "notionPageUrl": "https://www.notion.so/original", "slackChannelId": "team-channel",
        "members": [{"userId": 7, "email": "member@example.com"}],
    })

    assert resp.status_code == 200
    assert resp.json() == {
        "notion": {"status": "CONNECTED", "value": "https://www.notion.so/normalized"},
        "slack": {"status": "CONNECTED", "value": "C0123456789"},
        "members": [{"userId": 7, "email": "member@example.com", "status": "CONNECTED", "slackUserId": "U0123456789"}],
    }
    assert notion.calls == [{"page_url_or_id": "https://www.notion.so/original"}]
    assert channel.calls == [{"channel": "team-channel"}]
    assert user.calls == [{"email": "member@example.com"}]


def test_한_도구_실패가_다른_연결_확인을_막지_않는다(client, settings):
    main.app.state.overrides = {"settings": settings, "mcp_tools": {
        "check_notion_page": FakeTool(error=RuntimeError("[NOTION_FORBIDDEN] no")),
        "check_slack_channel": FakeTool({"status": "NOT_CONNECTED", "channel_id": "C1"}),
        "lookup_slack_user": FakeTool({"status": "NOT_FOUND", "email": "missing@example.com", "slack_user_id": None}),
    }}
    resp = client.post("/projects/check-connections", json={
        "notionPageUrl": "https://www.notion.so/x", "slackChannelId": "C1",
        "members": [{"userId": 7, "email": "missing@example.com"}],
    })
    assert resp.status_code == 200
    assert resp.json()["notion"]["status"] == "ERROR"
    assert resp.json()["slack"]["status"] == "ERROR"
    assert resp.json()["members"] == [{"userId": 7, "email": "missing@example.com", "status": "NOT_CONNECTED", "slackUserId": None}]
