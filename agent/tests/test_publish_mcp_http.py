"""실제 Agent MCP adapter → HTTP → Business MCP → 외부 API 대역 연동.

LLM/실제 외부 서비스는 호출하지 않는다. 기능 복구 계약 검증이며 성능 평가가 아니다.
"""
import asyncio
import os
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


@pytest.fixture(scope="module")
def mcp_url():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    # 실제 토큰/.env를 읽지 않는 전용 서버. 네트워크 요청은 모두 MockTransport로 고정.
    # Business MCP와 Agent는 각자 잠긴 SDK 버전/가상환경을 사용한다.
    server_python = Path(os.environ.get("BUSINESS_MCP_PYTHON", str(Path(__file__).resolve().parents[2] / "business-mcp/.venv/bin/python")))
    if not server_python.exists():
        pytest.skip("실제 HTTP 통합 테스트는 Business MCP 가상환경 또는 BUSINESS_MCP_PYTHON이 필요합니다")
    process = subprocess.Popen([str(server_python), str(Path(__file__).parent / "fixtures/publish_recovery_mcp.py"), str(port)],
                               env={"PATH": os.environ.get("PATH", "")}, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(100):
            if process.poll() is not None:
                pytest.fail("로컬 MCP 테스트 서버 기동 실패")
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=.1):
                    break
            except OSError:
                time.sleep(.05)
        else:
            pytest.fail("로컬 MCP 테스트 서버 준비 시간 초과")
        yield f"http://127.0.0.1:{port}/mcp"
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
async def test_hung_external_write_holds_publish_batch_without_agent_deadline(mcp_url):
    """#299 재현: 외부 쓰기가 응답하지 않으면 Agent에는 묶음 마감이 없어 MCP 도구 응답을 계속 기다린다.

    실제로는 MCP 도구 안의 외부 요청 시간 초과(20초)나 MCP 클라이언트 응답 대기 기본값(5분)이 끝나야 묶음이 끝난다.
    #302에서 묶음 마감을 넣으면 이 테스트를 기대 동작(마감 안에 결과 모름으로 끝남)으로 바꾼다.
    """
    settings = Settings(mcp_server_url=mcp_url, rag_enabled=False, dry_run=False)
    tools = await github_tools(settings)
    await tools["fixture_mode"].ainvoke({"mode": "hang"})
    try:
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(
                run_publish_batch(PublishBatchRequest(batch_id="hang", tickets=[ticket()]), settings), timeout=2)
        state = parse_tool_result(await tools["fixture_snapshot"].ainvoke({}))
        # 이슈 생성 요청은 이미 외부로 나갔다. 마감으로 끊더라도 재시도 가능 실패가 아니라 결과 모름이어야 하는 이유다
        assert [(w["method"], w["path"]) for w in state["writes"]] == [("POST", "/repos/o/r/issues")]
    finally:
        await tools["fixture_mode"].ainvoke({"mode": "ok"})
