"""#299: 발행이 멈추거나 오래 기다리는 지점의 현재 동작을 고정한다.

실제 MCP HTTP 서버가 필요한 외부 쓰기 무응답은 test_publish_mcp_http.py에 있다.
"#302에서 바꿀 현재 동작"이라고 적은 테스트는 시간 상한을 넣을 때 기대 동작으로 바꾼다.
"""
import asyncio
import socket
import time
from datetime import timedelta

import pytest
from fastapi.testclient import TestClient

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


def test_mcp_client_uses_library_default_timeouts():
    """#302에서 바꿀 현재 동작: MCP 연결에 시간 상한을 주지 않아 라이브러리 기본값(연결 30초, 응답 대기 5분)을 쓴다."""
    from langchain_mcp_adapters import sessions
    from runner import _mcp_client

    connection = _mcp_client("github", "http://127.0.0.1:8000/mcp").connections["github"]
    assert "timeout" not in connection and "sse_read_timeout" not in connection
    assert sessions.DEFAULT_STREAMABLE_HTTP_TIMEOUT == timedelta(seconds=30)
    assert sessions.DEFAULT_STREAMABLE_HTTP_SSE_READ_TIMEOUT == timedelta(minutes=5)


def test_restarted_agent_cannot_tell_lost_batch_from_unknown_batch(monkeypatch):
    """#302에서 바꿀 현재 동작: 발행 묶음 기록은 프로세스 메모리에만 있다.

    재시작하면 처리 중이던 묶음도 처음 보는 batchId와 같은 404가 되고, Backend는 이를 결과 없음으로 보고
    결과 대기 기한(기본 30분)까지 기다린다 (backend PublishBatchTest).
    """
    import main

    monkeypatch.setattr(main, "_publish_jobs", {"running": {"batchId": "running", "status": "RUNNING"}})
    client = TestClient(main.app)
    assert client.get("/publish/running").json()["status"] == "RUNNING"

    monkeypatch.setattr(main, "_publish_jobs", {})  # 재시작
    lost, never_sent = client.get("/publish/running"), client.get("/publish/never-sent")
    assert lost.status_code == never_sent.status_code == 404
    assert lost.json() == never_sent.json()


def test_failed_result_callback_still_leaves_result_for_backend_polling(monkeypatch):
    """결과 콜백이 실패해도 결과는 GET /publish/{batchId}로 남아, Backend 조회(5초 주기)로 묶음이 끝난다."""
    import main

    class DownReporter:
        async def publish_result(self, batch_id, payload):
            return False  # 재시도까지 모두 실패

    monkeypatch.setattr(main, "_publish_jobs", {})
    monkeypatch.setattr(main.app.state, "overrides", {"settings": Settings(rag_enabled=False, dry_run=True),
                                                      "reporter": DownReporter()})
    payload = PublishBatchRequest(batch_id="callback-down", tickets=[ticket()]).model_dump(by_alias=True, mode="json")
    with TestClient(main.app) as client:
        assert client.post("/publish", json=payload).status_code == 202
        for _ in range(200):
            body = client.get("/publish/callback-down").json()
            if body["status"] != "RUNNING":
                break
            time.sleep(.01)
    assert body["status"] != "RUNNING" and len(body["items"]) == 1


@pytest.mark.anyio
async def test_unreachable_rag_mcp_does_not_hold_publish_batch():
    """rag-mcp가 꺼져 있으면 재대조만 건너뛰고 발행은 바로 진행한다 (연결 거부는 기다리지 않는다)."""
    settings = Settings(rag_enabled=True, rag_mcp_url=closed_port_url(), dry_run=True)
    started = time.monotonic()
    result = await asyncio.wait_for(
        run_publish_batch(PublishBatchRequest(batch_id="rag-down", tickets=[ticket()]), settings, llm=object()), timeout=10)
    assert len(result.items) == 1
    assert time.monotonic() - started < 5
