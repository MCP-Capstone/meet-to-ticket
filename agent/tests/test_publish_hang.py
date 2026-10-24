"""#299에서 재현한 발행 멈춤 지점이 #302의 시간 상한 안에 끝나는지 확인한다.

실제 MCP HTTP 서버가 필요한 외부 쓰기 무응답은 test_publish_mcp_http.py에, Agent 재시작 후 404는 test_publish_result_api.py에 있다.
"""
import asyncio
import socket
import time
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

import runner
from batch_publish import publish_batch
from config import Settings
from runner import run_publish_batch
from schemas import Project, PublishBatchRequest, PublishTicket


def ticket(key="t"):
    return PublishTicket(ticket_id=key, title="회의 후속 작업", meeting_id=1, meeting_title="회의",
                         project=Project(github_owner="o", github_repo="r"),
                         content_json={"description": "담당자가 승인한 작업"}, publish_target="GITHUB",
                         processing_type="CREATE", review_status="CONFIRMED")


def closed_port_url() -> str:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    return f"http://127.0.0.1:{port}/mcp"


class SlowPublisher:
    """hang_first면 첫 이슈 생성 요청을 보낸 뒤 응답하지 않는다."""

    def __init__(self, hang_first: bool = True):
        self.hang_first = hang_first
        self.calls = []

    async def create_issue(self, **args):
        self.calls.append(args["title"])
        if self.hang_first and len(self.calls) == 1:
            await asyncio.sleep(60)
        return {"number": 100 + len(self.calls), "url": "issue-url"}


class HangingIndexTools:
    async def index(self, number):
        await asyncio.sleep(60)


def test_mcp_client_applies_configured_timeouts():
    """MCP 연결·응답 대기 상한을 설정값으로 건다. 응답 대기는 read_timeout_seconds로만 끝난다 (#299)."""
    from runner import _mcp_client

    settings = Settings(mcp_http_timeout_seconds=7, mcp_call_timeout_seconds=45)
    connection = _mcp_client("github", "http://127.0.0.1:8000/mcp", settings).connections["github"]
    assert connection["timeout"] == 7
    assert connection["session_kwargs"] == {"read_timeout_seconds": timedelta(seconds=45)}

    off = _mcp_client("github", "http://127.0.0.1:8000/mcp", Settings(mcp_call_timeout_seconds=0)).connections["github"]
    assert "session_kwargs" not in off
    # 전체 재색인(reindex.py)은 오래 걸리므로 settings 없이 만들어 상한을 두지 않는다
    assert "timeout" not in _mcp_client("rag", "http://127.0.0.1:8002/mcp").connections["rag"]


@pytest.mark.anyio
async def test_publish_deadline_leaves_inflight_unknown_and_unsent_retryable():
    """마감에 걸리면 처리 중이던 티켓은 결과 모름, 시작하지 않은 티켓은 보내지 않은 재시도 가능 실패다."""
    publisher = SlowPublisher()
    request = PublishBatchRequest(batch_id="deadline", tickets=[ticket("first"), ticket("second")])
    started = time.monotonic()
    result = await publish_batch(request, publisher, deadline=time.monotonic() + 0.5)
    assert time.monotonic() - started < 2
    first, second = result.items
    assert (first.status, first.error_code, first.retryable) == ("UNKNOWN", "PUBLISH_DEADLINE", True)
    assert (second.status, second.error_code, second.retryable) == ("FAILED", "PUBLISH_DEADLINE", True)
    assert len(publisher.calls) == 1 and result.status == "FAILED"


@pytest.mark.anyio
async def test_unreachable_business_mcp_reports_every_ticket_unsent():
    """발행 MCP 도구를 가져오지 못하면 아무것도 보내지 않았으므로 결과 모름이 아니라 재시도 가능 실패로 보고한다."""
    settings = Settings(mcp_server_url=closed_port_url(), rag_enabled=False, dry_run=False)
    result = await asyncio.wait_for(
        run_publish_batch(PublishBatchRequest(batch_id="mcp-down", tickets=[ticket("a"), ticket("b")]), settings), timeout=10)
    assert result.status == "FAILED"
    assert {(i.status, i.error_code, i.retryable) for i in result.items} == {("FAILED", "MCP_UNAVAILABLE", True)}


@pytest.mark.anyio
async def test_hung_rag_tool_listing_is_bounded(monkeypatch):
    """응답 없이 멈춘 rag-mcp의 도구 목록 조회도 응답 대기 상한 안에 끝나고, 재대조 없이 진행한다."""
    class HangingClient:
        async def get_tools(self, server_name):
            await asyncio.sleep(60)

    monkeypatch.setattr(runner, "_mcp_client", lambda *args, **kwargs: HangingClient())
    settings = Settings(rag_enabled=True, mcp_call_timeout_seconds=1)
    started = time.monotonic()
    tools, status = await runner.make_issue_tools(settings, "o", "r", None)
    assert (tools, status) == (None, "unavailable")
    assert time.monotonic() - started < 3


@pytest.mark.anyio
async def test_hung_index_after_publish_does_not_hold_result(monkeypatch):
    """발행 후 색인이 멈춰도 발행 결과는 그대로 시간 안에 돌려준다 (#299에서는 묶음이 RUNNING으로 남았다)."""
    import recheck

    async def ready_tools(settings, owner, repo, github):
        return HangingIndexTools(), "ready"

    async def unchanged(tickets, **kwargs):
        return {"tickets": tickets}

    monkeypatch.setattr(runner, "make_issue_tools", ready_tools)
    monkeypatch.setattr(recheck, "recheck", unchanged)
    settings = Settings(rag_enabled=True, rag_sync_timeout_seconds=1)
    started = time.monotonic()
    result = await run_publish_batch(PublishBatchRequest(batch_id="index", tickets=[ticket()]), settings,
                                     publisher=SlowPublisher(hang_first=False), llm=object())
    assert time.monotonic() - started < 3
    assert [(i.status, i.github_issue_number) for i in result.items] == [("PUBLISHED", 101)]


def test_result_stays_readable_after_result_callback_fails(monkeypatch):
    """결과 콜백이 재시도까지 실패해도 결과는 GET /publish/{batchId}에 남아, Backend 조회(5초 주기)로 묶음이 끝난다."""
    import main

    class DownReporter:
        def __init__(self):
            self.calls = []

        async def publish_result(self, batch_id, payload):
            self.calls.append(batch_id)
            return False  # 재시도까지 모두 실패

    reporter = DownReporter()
    monkeypatch.setattr(main, "_publish_jobs", {})
    monkeypatch.setattr(main.app.state, "overrides", {"settings": Settings(rag_enabled=False, dry_run=True),
                                                      "reporter": reporter})
    payload = PublishBatchRequest(batch_id="callback-down", tickets=[ticket()]).model_dump(by_alias=True, mode="json")
    with TestClient(main.app) as client:
        assert client.post("/publish", json=payload).status_code == 202
        for _ in range(200):
            if reporter.calls:
                break
            time.sleep(.01)
        assert reporter.calls == ["callback-down"]
        body = client.get("/publish/callback-down").json()
    assert body["status"] != "RUNNING" and len(body["items"]) == 1


@pytest.mark.anyio
async def test_refused_rag_mcp_connection_does_not_hold_publish_batch():
    """rag-mcp 연결이 거부되면(꺼짐) 재대조만 건너뛰고 발행은 바로 진행한다."""
    settings = Settings(rag_enabled=True, rag_mcp_url=closed_port_url(), dry_run=True)
    started = time.monotonic()
    result = await asyncio.wait_for(
        run_publish_batch(PublishBatchRequest(batch_id="rag-down", tickets=[ticket()]), settings, llm=object()), timeout=10)
    assert len(result.items) == 1
    assert time.monotonic() - started < 5
