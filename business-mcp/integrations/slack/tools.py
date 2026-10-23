"""Slack MCP 도구. 채널·사용자 정보는 Backend → Agent가 인자로 넣고 MCP는 토큰만 관리한다."""

from __future__ import annotations

from typing import Any

from mcp.server.mcpserver import MCPServer

from common import READ, WRITE, titled
from integrations.slack import api


def check_slack_channel(channel: str) -> dict[str, Any]:
    """채널 이름 또는 ID로 채널 존재·봇 멤버십을 확인하고 ID를 돌려준다."""
    with api.make_client() as client:
        return api.check_slack_channel(client, channel)


def lookup_slack_user(email: str) -> dict[str, Any]:
    """이메일로 Slack 사용자 ID를 찾는다. 찾지 못하면 status=NOT_FOUND를 돌려준다."""
    with api.make_client() as client:
        return api.lookup_slack_user(client, email)


def post_message(channel_id: str, text: str, mentions: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    """프로젝트 채널에 메시지 한 개를 게시한다. 재호출하면 메시지가 하나 더 생긴다."""
    with api.make_client() as client:
        return api.post_message(client, channel_id, text, mentions)


def register(mcp: MCPServer) -> None:
    """Slack 도구를 MCP 서버에 등록한다."""
    mcp.tool(
        description=(
            "프로젝트 Slack 채널 이름 또는 ID가 존재하고 봇이 초대됐는지 확인한다. "
            "채널 이름을 입력하면 channel_id를 함께 돌려준다."
        ),
        annotations=titled(READ, "Slack 채널 연결 확인"),
    )(check_slack_channel)
    mcp.tool(
        description="프로젝트 멤버의 이메일로 Slack 사용자 ID와 표시 이름을 찾는다. 없으면 status=NOT_FOUND를 돌려준다.",
        annotations=titled(READ, "Slack 사용자 조회"),
    )(lookup_slack_user)
    mcp.tool(
        description=(
            "프로젝트 Slack 채널에 검토·회의 결과 공지를 한 개 게시한다. mentions의 name과 정확히 일치하는 "
            "@이름만 Slack 멘션으로 치환하며, 스레드는 사용하지 않는다. 재호출하면 메시지가 추가된다."
        ),
        annotations=titled(WRITE, "Slack 프로젝트 채널 공지 게시"),
    )(post_message)
