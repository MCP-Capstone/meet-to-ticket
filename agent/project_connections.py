"""프로젝트 저장 시의 Notion·Slack 연결 확인 (Backend → Agent → business-mcp).

LLM을 거치지 않고, 한 외부 연동이 실패해도 나머지 확인 결과는 돌려준다. Backend는 이
결과를 저장해 다음 프로젝트 조회에서 그대로 보여준다.
"""

from __future__ import annotations

from typing import Any

from config import Settings
from publisher import parse_tool_result
from schemas import (
    ConnectionCheckOut,
    ProjectConnectionsRequest,
    ProjectConnectionsResponse,
    SlackUserCheckOut,
)


async def _tools(settings: Settings) -> dict[str, Any]:
    # 기존 멤버 권한 확인과 동일한 business-mcp 연결을 사용한다. runner 자체의 실행 로직에는
    # 관여하지 않고 도구 목록을 읽기만 한다.
    from runner import github_tools

    return await github_tools(settings)


async def _invoke(tool: Any | None, args: dict[str, Any], name: str) -> dict[str, Any]:
    if tool is None:
        raise RuntimeError(f"MCP 서버에 {name} 도구가 없음")
    raw = await tool.ainvoke(args)
    return parse_tool_result(raw)


async def check_project_connections(
    settings: Settings,
    req: ProjectConnectionsRequest,
    tools: dict[str, Any] | None = None,
) -> ProjectConnectionsResponse:
    """Notion 페이지, Slack 채널, 멤버 Slack ID를 서로 독립적으로 확인한다."""
    if tools is None:
        try:
            tools = await _tools(settings)
        except Exception:  # noqa: BLE001 - 오류는 각 상태에 남기고 저장은 계속한다.
            tools = {}

    notion = ConnectionCheckOut(status="NOT_CONNECTED")
    if req.notion_page_url:
        try:
            data = await _invoke(tools.get("check_notion_page"), {"page_url_or_id": req.notion_page_url}, "check_notion_page")
            notion = ConnectionCheckOut(status="CONNECTED", value=data.get("url") or req.notion_page_url)
        except Exception:  # noqa: BLE001 - 연결 상태 API는 외부 오류를 502로 바꾸지 않는다.
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

    members: list[SlackUserCheckOut] = []
    for member in req.members:
        try:
            data = await _invoke(tools.get("lookup_slack_user"), {"email": member.email}, "lookup_slack_user")
            if data.get("status") == "CONNECTED" and data.get("slack_user_id"):
                members.append(SlackUserCheckOut(
                    user_id=member.user_id, email=member.email, status="CONNECTED", slack_user_id=data["slack_user_id"],
                ))
            else:
                members.append(SlackUserCheckOut(user_id=member.user_id, email=member.email, status="NOT_CONNECTED"))
        except Exception:  # noqa: BLE001
            members.append(SlackUserCheckOut(user_id=member.user_id, email=member.email, status="ERROR"))

    return ProjectConnectionsResponse(notion=notion, slack=slack, members=members)
