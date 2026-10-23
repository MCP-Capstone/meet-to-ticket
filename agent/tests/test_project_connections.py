"""프로젝트 저장용 Notion·Slack 연결 확인 API."""

from __future__ import annotations

import json

from fastapi.testclient import TestClient

import main


class FakeTool:
    def __init__(self, result: dict):
        self.result = result
        self.calls: list[dict] = []

    async def ainvoke(self, args):
        self.calls.append(args)
        return json.dumps(self.result)


def test_프로젝트_저장용_연결을_확인하고_정규화된_값을_돌려준다(settings):
    notion = FakeTool({"page_id": "p1", "url": "https://www.notion.so/normalized"})
    channel = FakeTool({"status": "CONNECTED", "channel_id": "C0123456789"})
    user = FakeTool({"status": "CONNECTED", "email": "member@example.com", "slack_user_id": "U0123456789"})
    main.app.state.overrides = {"settings": settings, "mcp_tools": {
        "check_notion_page": notion, "check_slack_channel": channel, "lookup_slack_user": user,
    }}
    try:
        response = TestClient(main.app).post("/projects/check-connections", json={
            "notionPageUrl": "https://www.notion.so/original", "slackChannelId": "team-channel",
            "members": [{"userId": 7, "email": "member@example.com"}],
        })
    finally:
        main.app.state.overrides = {}

    assert response.status_code == 200
    assert response.json() == {
        "notion": {"status": "CONNECTED", "value": "https://www.notion.so/normalized"},
        "slack": {"status": "CONNECTED", "value": "C0123456789"},
        "members": [{"userId": 7, "email": "member@example.com", "status": "CONNECTED", "slackUserId": "U0123456789"}],
    }
    assert notion.calls == [{"page_url_or_id": "https://www.notion.so/original"}]
    assert channel.calls == [{"channel": "team-channel"}]
    assert user.calls == [{"email": "member@example.com"}]
