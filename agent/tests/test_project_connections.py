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


def test_멤버_조회는_동시에_실행되고_요청_순서대로_반환한다(settings):
    import asyncio

    from project_connections import check_project_connections
    from schemas import ProjectConnectionsRequest

    class SlowUser:
        def __init__(self):
            self.running = 0
            self.peak = 0

        async def ainvoke(self, args):
            self.running += 1
            self.peak = max(self.peak, self.running)
            await asyncio.sleep(0.01)
            self.running -= 1
            return json.dumps({"status": "CONNECTED", "slack_user_id": "U" + args["email"][0]})

    user = SlowUser()
    req = ProjectConnectionsRequest.model_validate({
        "members": [{"userId": i, "email": f"{i}@example.com"} for i in range(1, 5)],
    })
    result = asyncio.run(check_project_connections(settings, req, tools={"lookup_slack_user": user}))

    assert user.peak > 1
    assert [m.user_id for m in result.members] == [1, 2, 3, 4]
    assert [m.slack_user_id for m in result.members] == ["U1", "U2", "U3", "U4"]
