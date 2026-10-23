"""프로젝트 저장 시 Notion·Slack 연결 확인 (Backend → Agent → business-mcp)."""

from __future__ import annotations

import asyncio
from typing import Any

from config import Settings
from publisher import parse_tool_result
from schemas import ConnectionCheckOut, ProjectConnectionsRequest, ProjectConnectionsResponse, SlackUserCheckOut

_MEMBER_LOOKUP_CONCURRENCY = 5


async def _tools(settings: Settings) -> dict[str, Any]:
    # 프로젝트 멤버 권한 확인과 같은 business-mcp 연결을 재사용한다.
    from runner import github_tools

    return await github_tools(settings)


async def _invoke(tool: Any | None, args: dict[str, Any], name: str) -> dict[str, Any]:
    if tool is None:
        raise RuntimeError(f"MCP 서버에 {name} 도구가 없음")
    return parse_tool_result(await tool.ainvoke(args))


async def check_project_connections(
    settings: Settings,
    req: ProjectConnectionsRequest,
    tools: dict[str, Any] | None = None,
) -> ProjectConnectionsResponse:
    """각 외부 연결을 독립 확인해, 한 쪽의 오류가 저장을 막지 않게 한다."""
    if tools is None:
        try:
            tools = await _tools(settings)
        except Exception:  # noqa: BLE001 - 오류는 연결 상태로 반환한다.
            tools = {}

    notion = ConnectionCheckOut(status="NOT_CONNECTED")
    if req.notion_page_url:
        try:
            data = await _invoke(tools.get("check_notion_page"), {"page_url_or_id": req.notion_page_url}, "check_notion_page")
            notion = ConnectionCheckOut(status="CONNECTED", value=data.get("url") or req.notion_page_url)
        except Exception:  # noqa: BLE001
            notion = ConnectionCheckOut(status="ERROR")

    slack = ConnectionCheckOut(status="NOT_CONNECTED")
    if req.slack_channel_id:
        try:
            data = await _invoke(tools.get("check_slack_channel"), {"channel": req.slack_channel_id}, "check_slack_channel")
            if data.get("status") == "CONNECTED" and data.get("channel_id"):
                slack = ConnectionCheckOut(status="CONNECTED", value=data["channel_id"])
            else:
                slack = ConnectionCheckOut(status="ERROR")
        except Exception:  # noqa: BLE001
            slack = ConnectionCheckOut(status="ERROR")

    # 멤버 조회는 서로 독립이므로 동시에 실행한다. 순차 실행은 팀이 크면 Backend 읽기 제한 시간을 넘긴다.
    gate = asyncio.Semaphore(_MEMBER_LOOKUP_CONCURRENCY)

    async def check_member(member: Any) -> SlackUserCheckOut:
        async with gate:
            try:
                data = await _invoke(tools.get("lookup_slack_user"), {"email": member.email}, "lookup_slack_user")
                if data.get("status") == "CONNECTED" and data.get("slack_user_id"):
                    return SlackUserCheckOut(
                        user_id=member.user_id, email=member.email, status="CONNECTED", slack_user_id=data["slack_user_id"],
                    )
                return SlackUserCheckOut(user_id=member.user_id, email=member.email, status="NOT_CONNECTED")
            except Exception:  # noqa: BLE001
                return SlackUserCheckOut(user_id=member.user_id, email=member.email, status="ERROR")

    members = list(await asyncio.gather(*(check_member(m) for m in req.members)))

    return ProjectConnectionsResponse(notion=notion, slack=slack, members=members)
