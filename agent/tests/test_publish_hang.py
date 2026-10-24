"""#299: 발행이 멈추거나 오래 기다리는 지점의 현재 동작을 고정한다.

실제 MCP HTTP 서버가 필요한 외부 쓰기 무응답은 test_publish_mcp_http.py에, Agent 재시작 후 404는 test_publish_result_api.py에 있다.
"#302에서 바꿀 현재 동작"이라고 적은 테스트는 시간 상한을 넣을 때 기대 동작으로 바꾼다.
"""
import asyncio
import socket
import time

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


def test_mcp_client_sets_no_timeouts():
    """#302에서 바꿀 현재 동작: MCP 연결에 시간 상한을 하나도 넘기지 않는다.

    ClientSession read_timeout_seconds가 없으면 도구 호출 응답을 끝없이 기다린다. 라이브러리의 응답 스트림 읽기
    시간 초과는 mcp 클라이언트가 예외를 삼키고 스트림만 끝내므로 호출을 끝내지 못한다. 그래서 대기 상한이 사실상 없다.
    """
    from runner import _mcp_client

    connection = _mcp_client("github", "http://127.0.0.1:8000/mcp").connections["github"]
    assert "timeout" not in connection and "sse_read_timeout" not in connection
    assert "read_timeout_seconds" not in (connection.get("session_kwargs") or {})


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
    """rag-mcp 연결이 거부되면(꺼짐) 재대조만 건너뛰고 발행은 바로 진행한다.

    응답 없이 멈춘 rag-mcp는 다르다: 도구 목록 조회(runner.make_issue_tools)와 발행 후 색인(IssueTools.index)에
    시간 상한이 없어 묶음이 끝나지 않는다. #302 범위다.
    """
    settings = Settings(rag_enabled=True, rag_mcp_url=closed_port_url(), dry_run=True)
    started = time.monotonic()
    result = await asyncio.wait_for(
        run_publish_batch(PublishBatchRequest(batch_id="rag-down", tickets=[ticket()]), settings, llm=object()), timeout=10)
    assert len(result.items) == 1
    assert time.monotonic() - started < 5
