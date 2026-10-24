"""로컬 HTTP 통합 테스트 전용. 실제 Business MCP 도구 + 외부 API MockTransport.

실제 API 클라이언트는 만들지 않으며 허용하지 않은 요청은 즉시 실패한다.
"""
import json
import sys
import threading
from pathlib import Path

import httpx
from mcp.server.mcpserver import MCPServer

sys.path.insert(0, str(Path(__file__).resolve().parents[3] / "business-mcp"))
from integrations.github import api as github, tools as github_tools
from integrations.notion import api as notion, tools as notion_tools
from integrations.slack import api as slack, tools as slack_tools

# release: hang 모드에서 쓰기 응답을 붙잡아 두는 신호. fixture_mode가 set한 채로 새 신호로 바꾸므로,
# 늦게 wait에 들어간 요청도 바로 풀린다. 풀리지 않아도 HANG_MAX_SECONDS 뒤에는 끝난다
state = {"mode": "ok", "writes": [], "body": "원래 본문", "comments": [], "release": threading.Event()}
HANG_MAX_SECONDS = 30


def handler(request):
    path, method = request.url.path, request.method
    payload = json.loads(request.content) if request.content else {}
    if method in {"POST", "PATCH"}:
        state["writes"].append({"method": method, "path": path, "payload": payload})
        mode, release = state["mode"], state["release"]
        if mode == "hang":
            # 외부 서비스가 요청을 받고 응답하지 않는 상황 (#299). 응답은 끝내 오지 않은 것으로 끝낸다
            release.wait(HANG_MAX_SECONDS)
            raise httpx.ReadTimeout("no response after write", request=request)
        if state["mode"] == "connect-fail":
            raise httpx.ConnectError("connection refused before send", request=request)
        if state["mode"] == "disconnect":
            raise httpx.ReadError("connection closed after write", request=request)
        if path.endswith("/comments") and state["mode"] == "comment-fail":
            state["mode"] = "ok"
            return httpx.Response(401, json={"message": "comment denied"})
        if path.endswith("/issues"):
            return httpx.Response(201, json={"number": 101, "html_url": "https://github.com/o/r/issues/101", "assignees": []})
        if path.endswith("/comments"):
            comment = {"body": payload["body"], "html_url": "https://github.com/o/r/issues/7#issuecomment-1", "id": 1}
            state["comments"].append(comment)
            if state["mode"] == "comment-response-lost":
                state["mode"] = "ok"
                raise httpx.ReadError("comment response lost", request=request)
            return httpx.Response(201, json=comment)
        if method == "PATCH" and "/issues/" in path:
            state["body"] = payload["body"]
            return httpx.Response(200, json={"number": 7, "html_url": "https://github.com/o/r/issues/7"})
        if path == "/v1/pages":
            return httpx.Response(200, json={"id": "33333333-3333-3333-3333-333333333333", "url": "https://app.notion.com/p/result"})
        if path == "/api/chat.postMessage":
            return httpx.Response(200, json={"ok": True, "ts": "1.2"})
    if method == "GET":
        if path.endswith("/comments"):
            return httpx.Response(200, json=state["comments"])
        if "/issues/" in path:
            return httpx.Response(200, json={"number": 7, "body": state["body"], "html_url": "https://github.com/o/r/issues/7", "labels": []})
        if path.endswith("/labels"):
            return httpx.Response(200, json=[])
        if path.endswith("/children"):
            return httpx.Response(200, json={"results": [{"id": "22222222-2222-2222-2222-222222222222", "type": "child_page", "child_page": {"title": notion.TICKET_FOLDER_TITLE}}], "has_more": False})
        if path.startswith("/v1/pages/"):
            return httpx.Response(200, json={"id": "11111111-1111-1111-1111-111111111111"})
        if path == "/api/chat.getPermalink":
            if state["mode"] == "link-fail":
                return httpx.Response(200, json={"ok": False, "error": "missing_scope"})
            return httpx.Response(200, json={"ok": True, "permalink": "https://team.slack.com/archives/C12345678/p12"})
    raise AssertionError(f"허용하지 않은 외부 요청: {method} {path}")


for module in (github, notion, slack):
    module.make_client = lambda module=module: httpx.Client(base_url=module.API_BASE, transport=httpx.MockTransport(handler))

mcp = MCPServer("local-publish-recovery-fixture")
github_tools.register(mcp)
notion_tools.register(mcp)
slack_tools.register(mcp)


@mcp.tool()
def fixture_mode(mode: str) -> dict:
    # 이전 hang 모드에서 붙잡힌 요청을 풀어 준다. 이전 신호는 set인 채로 두어 늦게 도착한 요청도 기다리지 않는다
    state["release"].set()
    state.update(mode=mode, writes=[], body="원래 본문", comments=[], release=threading.Event())
    return {"mode": mode}


@mcp.tool()
def fixture_snapshot() -> dict:
    return {key: value for key, value in state.items() if key != "release"}


if __name__ == "__main__":
    mcp.run(transport="streamable-http", host="127.0.0.1", port=int(sys.argv[1]))
