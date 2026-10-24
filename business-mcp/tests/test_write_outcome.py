import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from common import request_with_outcome
from integrations.slack import api as slack
from integrations.notion import api as notion


@pytest.mark.parametrize("error,code", [(httpx.ConnectError("refused"), "MCP_UNAVAILABLE"),
                                        (httpx.PoolTimeout("pool"), "MCP_UNAVAILABLE"),
                                        (httpx.ReadError("closed"), "RESULT_UNKNOWN"),
                                        (httpx.WriteError("broken"), "RESULT_UNKNOWN"),
                                        (httpx.ReadTimeout("timeout"), "RESULT_UNKNOWN")])
def test_write_transport_phase_survives_mcp_error_serialization(error, code):
    def fail(request):
        raise error
    with httpx.Client(transport=httpx.MockTransport(fail)) as client:
        with pytest.raises(ToolError, match=rf"^\[{code}\]"):
            request_with_outcome(client, "POST", "https://fixture.invalid/write")


def test_slack_posted_but_permalink_denied_is_not_safe_to_repost():
    calls = []
    def handler(request):
        calls.append(request.url.path)
        if request.method == "POST":
            return httpx.Response(200, json={"ok": True, "ts": "1.2"})
        return httpx.Response(200, json={"ok": False, "error": "missing_scope"})
    with httpx.Client(base_url=slack.API_BASE, transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ToolError, match=r"^\[RESULT_UNKNOWN\].*게시됐지만"):
            slack.post_message(client, "C12345678", "공지")
    assert calls.count("/api/chat.postMessage") == 1


def test_notion_page_created_but_later_block_write_denied_requires_confirmation():
    root = "11111111-1111-1111-1111-111111111111"
    folder = "22222222-2222-2222-2222-222222222222"
    writes = []
    def handler(request):
        if request.method == "GET" and request.url.path.endswith("/children"):
            return httpx.Response(200, json={"results": [{"id": folder, "type": "child_page", "child_page": {"title": notion.TICKET_FOLDER_TITLE}}], "has_more": False})
        if request.method == "GET":
            return httpx.Response(200, json={"id": root})
        writes.append(request.method)
        if request.method == "POST":
            return httpx.Response(200, json={"id": "new-page", "url": "https://app.notion.com/p/new-page"})
        return httpx.Response(403, json={"message": "denied"})
    with httpx.Client(base_url=notion.API_BASE, transport=httpx.MockTransport(handler)) as client:
        with pytest.raises(ToolError, match=r"^\[RESULT_UNKNOWN\].*페이지 생성 후"):
            notion.create_page(client, root, "티켓", "\n\n".join(f"문단 {i}" for i in range(101)))
    assert writes == ["POST", "PATCH"]
