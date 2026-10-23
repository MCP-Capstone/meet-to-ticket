"""Notion MCP 도구. 페이지 범위는 Backend → Agent가 인자로 넣고 MCP는 토큰만 관리한다."""

from __future__ import annotations

from typing import Any

from mcp.server.mcpserver import MCPServer

from common import READ, WRITE, titled
from integrations.notion import api


def check_notion_page(page_url_or_id: str) -> dict[str, str]:
    """기획 페이지 접근 가능 여부를 확인하고 page_id·제목·URL을 돌려준다."""
    with api.make_client() as client:
        return api.check_notion_page(client, page_url_or_id)


def list_pages(root_page_url_or_id: str) -> dict[str, Any]:
    """기획 상위 페이지 아래의 맥락 문서 목록을 돌려준다. 자동 티켓 폴더는 제외한다."""
    with api.make_client() as client:
        return {"pages": api.list_pages(client, root_page_url_or_id)}


def read_page(page_url_or_id: str, max_chars: int = api.DEFAULT_READ_CHARS) -> dict[str, Any]:
    """Notion 문서 본문을 Markdown과 truncated 여부로 돌려준다."""
    with api.make_client() as client:
        return api.read_page(client, page_url_or_id, max_chars)


def create_page(root_page_url_or_id: str, title: str, markdown: str) -> dict[str, str]:
    """Notion 기획 페이지의 '회의 후속 티켓' 하위 페이지에 티켓을 하나 만든다."""
    with api.make_client() as client:
        return api.create_page(client, root_page_url_or_id, title, markdown)


def register(mcp: MCPServer) -> None:
    """Notion 도구를 서버에 등록한다."""
    mcp.tool(
        description="Notion 기획 페이지 URL 또는 ID가 서비스 Integration에 공유되었는지 확인한다.",
        annotations=titled(READ, "Notion 기획 페이지 연결 확인"),
    )(check_notion_page)
    mcp.tool(
        description="기획 상위 페이지 바로 아래 문서 목록을 조회한다. '회의 후속 티켓' 자동 생성 폴더는 제외한다.",
        annotations=titled(READ, "Notion 기획 문서 목록 조회"),
    )(list_pages)
    mcp.tool(
        description=(
            "Notion 문서 한 개를 Markdown으로 읽는다. max_chars는 1~12000이며, 본문이 더 길면 "
            "truncated=true를 돌려준다. Agent는 문서 목록에서 선택한 페이지만 읽는다."
        ),
        annotations=titled(READ, "Notion 기획 문서 조회"),
    )(read_page)
    mcp.tool(
        description=(
            "기획 상위 페이지 아래 '회의 후속 티켓' 폴더에 Markdown 티켓 페이지를 만든다. "
            "같은 요청을 다시 부르면 페이지가 하나 더 생긴다."
        ),
        annotations=titled(WRITE, "Notion 티켓 페이지 생성"),
    )(create_page)
