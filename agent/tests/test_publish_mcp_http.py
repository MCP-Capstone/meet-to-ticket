"""실제 Agent MCP adapter → HTTP → Business MCP → 외부 API 대역 연동.

LLM/실제 외부 서비스는 호출하지 않는다. 기능 복구 계약 검증이며 성능 평가가 아니다.
"""
import asyncio
import os
import signal
import socket
import subprocess
import time
from pathlib import Path

import pytest

from batch_publish import publish_one
from config import Settings
from publisher import parse_tool_result
from runner import github_tools, make_mcp_publisher, run_publish_batch
from schemas import Project, PublishBatchRequest, PublishTicket


def _start_server() -> tuple[subprocess.Popen, str]:
    """실제 토큰/.env를 읽지 않는 전용 서버. 네트워크 요청은 모두 MockTransport로 고정.

    Business MCP와 Agent는 각자 잠긴 SDK 버전/가상환경을 사용한다.
    """
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    server_python = Path(os.environ.get("BUSINESS_MCP_PYTHON", str(Path(__file__).resolve().parents[2] / "business-mcp/.venv/bin/python")))
    if not server_python.exists():
        pytest.skip("실제 HTTP 통합 테스트는 Business MCP 가상환경 또는 BUSINESS_MCP_PYTHON이 필요합니다")
    process = subprocess.Popen([str(server_python), str(Path(__file__).parent / "fixtures/publish_recovery_mcp.py"), str(port)],
                               env={"PATH": os.environ.get("PATH", "")}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(100):
        if process.poll() is not None:
            pytest.fail("로컬 MCP 테스트 서버 기동 실패")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=.1):
                return process, f"http://127.0.0.1:{port}/mcp"
        except OSError:
            time.sleep(.05)
    process.terminate()
    pytest.fail("로컬 MCP 테스트 서버 준비 시간 초과")


@pytest.fixture(scope="module")
def mcp_url():
    process, url = _start_server()
    try:
        yield url
    finally:
        process.terminate()
        process.wait(timeout=5)


def ticket(target="GITHUB", kind="CREATE", key="t"):
    return PublishTicket(ticket_id=key, title="회의 후속 작업", meeting_id=1,
                         meeting_title="회의", project=Project(github_owner="o", github_repo="r",
                         notion_page_url="11111111111111111111111111111111", slack_channel_id="C12345678"),
                         content_json={"description": "담당자가 승인한 작업"}, publish_target=target,
                         processing_type=kind, target_issue_number=7 if kind != "CREATE" else None,
                         processing_type_edited=True, review_status="CONFIRMED")


@pytest.mark.anyio
async def test_mixed_batch_publishes_every_target_via_real_mcp_protocol(mcp_url):
    settings = Settings(mcp_server_url=mcp_url, rag_enabled=False, dry_run=False)
    tools = await github_tools(settings)
    await tools["fixture_mode"].ainvoke({"mode": "ok"})
    result = await run_publish_batch(PublishBatchRequest(batch_id="mixed", tickets=[
        ticket(key="create"), ticket(kind="LINK", key="link"), ticket(kind="UPDATE", key="update"),
        ticket("NOTION", key="notion"), ticket("SLACK", key="slack")]), settings)
    assert [i.status for i in result.items] == ["PUBLISHED", "LINKED", "UPDATED", "PUBLISHED", "POSTED"]
    assert result.items[3].notion_page_url.startswith("https://app.notion.com/")
    assert result.items[4].message_url.startswith("https://team.slack.com/")


@pytest.mark.anyio
@pytest.mark.parametrize("mode,expected_comments", [("comment-fail", 2), ("comment-response-lost", 1)])
async def test_update_comment_recovery_never_reappends_body_over_http(mcp_url, mode, expected_comments):
    tools = await github_tools(Settings(mcp_server_url=mcp_url))
    await tools["fixture_mode"].ainvoke({"mode": mode})
    publisher = make_mcp_publisher(tools)
    source = ticket(kind="UPDATE")
    first = await publish_one(source, publisher)
    assert first.status == "UPDATED" and first.error_code == "COMMENT_FAILED"
    retry = source.model_copy(update={"retry_comment_only": True})
    assert (await publish_one(retry, publisher)).error_code is None
    assert (await publish_one(retry, publisher)).error_code is None
    state = parse_tool_result(await tools["fixture_snapshot"].ainvoke({}))
    assert sum(w["method"] == "PATCH" for w in state["writes"]) == 1
    assert sum(w["path"].endswith("/comments") for w in state["writes"]) == expected_comments
    assert len(state["comments"]) == 1


@pytest.mark.anyio
async def test_serialized_write_disconnects_and_slack_link_failure_are_unknown(mcp_url):
    tools = await github_tools(Settings(mcp_server_url=mcp_url))
    publisher = make_mcp_publisher(tools)
    await tools["fixture_mode"].ainvoke({"mode": "disconnect"})
    for target in ("GITHUB", "NOTION", "SLACK"):
        item = await publish_one(ticket(target), publisher)
        assert item.status == "UNKNOWN" and item.error_code == "RESULT_UNKNOWN"
    await tools["fixture_mode"].ainvoke({"mode": "link-fail"})
    item = await publish_one(ticket("SLACK"), publisher)
    assert item.status == "UNKNOWN" and item.error_code == "RESULT_UNKNOWN"


@pytest.mark.anyio
async def test_serialized_pre_send_connection_failure_remains_retryable(mcp_url):
    tools = await github_tools(Settings(mcp_server_url=mcp_url))
    await tools["fixture_mode"].ainvoke({"mode": "connect-fail"})
    for target in ("GITHUB", "NOTION", "SLACK"):
        item = await publish_one(ticket(target), make_mcp_publisher(tools))
        assert item.status == "FAILED" and item.error_code == "MCP_UNAVAILABLE" and item.retryable


@pytest.mark.anyio
@pytest.mark.parametrize("limits, code", [
    ({"mcp_call_timeout_seconds": 1, "publish_deadline_seconds": 0}, "PUBLISH_FAILED"),  # 도구 호출 대기 상한
    ({"mcp_call_timeout_seconds": 0, "publish_deadline_seconds": 1}, "RESULT_UNKNOWN"),  # 묶음 마감 (쓰기를 보낸 뒤)
])
async def test_hung_external_write_ends_as_unknown_within_limit(mcp_url, limits, code):
    """#302: 외부 쓰기가 응답하지 않아도 도구 호출 대기 상한이나 묶음 마감 안에 끝난다 (#299에서는 끝나지 않았다).

    이슈 생성 요청은 이미 외부로 나갔으므로 재시도 가능 실패가 아니라 결과 모름이다.
    """
    settings = Settings(mcp_server_url=mcp_url, rag_enabled=False, dry_run=False, **limits)
    tools = await github_tools(settings)
    await tools["fixture_mode"].ainvoke({"mode": "hang"})
    try:
        result = await asyncio.wait_for(
            run_publish_batch(PublishBatchRequest(batch_id="hang", tickets=[ticket()]), settings), timeout=5)
        [item] = result.items
        assert (item.status, item.error_code, item.retryable) == ("UNKNOWN", code, True)
        state = parse_tool_result(await tools["fixture_snapshot"].ainvoke({}))
        assert [(w["method"], w["path"]) for w in state["writes"]] == [("POST", "/repos/o/r/issues")]
    finally:
        await tools["fixture_mode"].ainvoke({"mode": "ok"})


@pytest.mark.anyio
async def test_frozen_mcp_server_ends_within_limits_including_session_close():
    """MCP 서버 프로세스가 통째로 멈춰도(docker pause와 같음) 연결 정리까지 상한 안에 끝난다 (#318 리뷰).

    묶음 마감이 응답 대기 중에 걸리면, 취소 정리로 보내는 세션 종료 요청(DELETE)은 취소되지 않고 sse_read_timeout까지
    기다린다. 설정하지 않으면 300초가 더 걸렸다. 기본값(도구 호출 90초, 마감 600초)에서 마지막 티켓이 마감 90초 안쪽에서
    시작한 경우와 같도록 도구 호출 상한을 마감보다 길게 둔다. 공용 서버를 멈추면 다른 테스트가 깨지므로 따로 띄운다.
    """
    process, url = _start_server()
    try:
        settings = Settings(mcp_server_url=url, rag_enabled=False, dry_run=False,
                            mcp_call_timeout_seconds=4, publish_deadline_seconds=2)
        tools = await github_tools(settings)
        await tools["fixture_mode"].ainvoke({"mode": "hang"})
        publisher = make_mcp_publisher(tools)

        async def freeze():
            await asyncio.sleep(.7)  # 이슈 생성 요청이 서버에 도착한 뒤 서버 전체를 멈춘다
            os.kill(process.pid, signal.SIGSTOP)

        freezer = asyncio.get_running_loop().create_task(freeze())
        started = time.monotonic()
        result = await asyncio.wait_for(
            run_publish_batch(PublishBatchRequest(batch_id="frozen", tickets=[ticket()]), settings, publisher=publisher),
            timeout=30)
        await freezer
        [item] = result.items
        assert item.status == "UNKNOWN" and item.retryable
        # 묶음 마감 2초 + 연결 정리 최대 4초 (sse_read_timeout = 도구 호출 상한)
        assert time.monotonic() - started < 10
    finally:
        # 붙잡힌 요청이 있어 정상 종료를 기다리지 않고 끝낸다
        os.kill(process.pid, signal.SIGCONT)
        process.kill()
        process.wait(timeout=5)
