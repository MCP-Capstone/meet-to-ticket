"""#283/#284: 외부 쓰기 대신 전송/응답 유실을 주입해 복구 경로를 검증한다."""
import httpx
import pytest

from batch_publish import publish_one, publication_preview
from config import Settings
from publisher import McpPublisher, ToolCallError
from runner import run_publish_batch
from schemas import Project, PublishBatchRequest, PublishTicket


@pytest.mark.parametrize("overrides", [
    {"publish_target": "NOTION"}, {"processing_type": "CREATE"}, {"target_issue_number": None},
])
def test_comment_retry_rejects_invalid_target_before_execution(overrides):
    from pydantic import ValidationError

    payload = source(comment_only=True).model_dump()
    with pytest.raises(ValidationError, match="댓글 재전송"):
        PublishTicket.model_validate({**payload, **overrides})


def source(target="GITHUB", comment_only=False):
    return PublishTicket(ticket_id="t1", project=Project(github_owner="o", github_repo="r",
                         notion_page_url="https://app.notion.com/root", slack_channel_id="C1"),
                         meeting_id=1, title="복구 테스트", meeting_title="회의",
                         content_json={"description": "변경 사항", "work_key": "WK-1"},
                         publish_target=target, processing_type="UPDATE" if comment_only else "CREATE",
                         target_issue_number=7 if comment_only else None,
                         review_status="CONFIRMED", retry_comment_only=comment_only)


class ThrowTool:
    def __init__(self, error):
        self.error = error

    async def ainvoke(self, args):
        raise self.error


@pytest.mark.anyio
@pytest.mark.parametrize("target", ["GITHUB", "NOTION", "SLACK"])
@pytest.mark.parametrize("error", [httpx.ReadError("connection closed"), httpx.WriteError("broken pipe"),
                                  ConnectionResetError("peer reset"), EOFError(), RuntimeError("transport lost")])
async def test_post_write_transport_errors_are_unknown_for_every_target(target, error):
    tool = ThrowTool(error)
    publisher = McpPublisher(tool, post_message_tool=tool, create_page_tool=tool)
    result = await publish_one(source(target), publisher)
    assert result.status == "UNKNOWN" and result.retryable


@pytest.mark.anyio
@pytest.mark.parametrize("target", ["GITHUB", "NOTION", "SLACK"])
@pytest.mark.parametrize("error", [httpx.ConnectError("refused"), httpx.ConnectTimeout("connect"), httpx.PoolTimeout("pool")])
async def test_proven_before_send_errors_remain_retryable_failed(target, error):
    tool = ThrowTool(error)
    result = await publish_one(source(target), McpPublisher(tool, post_message_tool=tool, create_page_tool=tool))
    assert result.status == "FAILED" and result.error_code == "MCP_UNAVAILABLE" and result.retryable


@pytest.mark.anyio
@pytest.mark.parametrize("target", ["GITHUB", "NOTION", "SLACK"])
async def test_missing_tool_never_sent_is_safe_to_retry(target):
    result = await publish_one(source(target), McpPublisher(None))
    assert result.status == "FAILED" and result.error_code == "MCP_UNAVAILABLE"


@pytest.mark.anyio
async def test_tagged_permission_rejection_is_not_unknown():
    result = await publish_one(source(), McpPublisher(ThrowTool(Exception("[GITHUB_AUTH] denied"))))
    assert result.status == "FAILED" and not result.retryable


class CommentPublisher:
    def __init__(self, *, found=False, truncated=False, lose_response=False):
        self.found, self.truncated, self.lose_response = found, truncated, lose_response
        self.writes = []

    async def find_marker(self, **args):
        assert "meettoticket:update-comment meeting=1 work_key=WK-1" in args["marker"]
        return {"found": self.found, "truncated": self.truncated, "url": "https://github.com/o/r/issues/7#issuecomment-1"}

    async def add_comment(self, **args):
        self.writes.append(args)
        assert "meettoticket:update-comment" in args["body"]
        self.found = True  # 실제 반영 후 응답만 잃는 상황
        if self.lose_response:
            raise ToolCallError("PUBLISH_FAILED", "response lost", ambiguous=True)
        return {"url": "https://github.com/o/r/issues/7#issuecomment-1"}

    async def update_issue(self, **args):
        pytest.fail("댓글 재전송에서 본문을 다시 수정하면 안 된다")


@pytest.mark.anyio
async def test_comment_retry_lost_response_then_retry_does_not_duplicate():
    publisher = CommentPublisher(lose_response=True)
    first = await publish_one(source(comment_only=True), publisher)
    assert first.status == "UPDATED" and first.error_code == "COMMENT_FAILED"
    second = await publish_one(source(comment_only=True), publisher)
    assert second.status == "UPDATED" and second.error_code is None
    assert len(publisher.writes) == 1


@pytest.mark.anyio
@pytest.mark.parametrize("found,truncated,writes,error", [(False, False, 1, None), (True, False, 0, None),
                                                          (False, True, 0, "COMMENT_FAILED")])
async def test_comment_retry_checks_marker_before_only_comment_write(found, truncated, writes, error):
    publisher = CommentPublisher(found=found, truncated=truncated)
    result = await publish_one(source(comment_only=True), publisher)
    assert result.status == "UPDATED" and result.error_code == error
    assert len(publisher.writes) == writes


@pytest.mark.anyio
async def test_comment_only_batch_skips_llm_and_recheck(monkeypatch):
    async def unexpected(*args, **kwargs):
        pytest.fail("댓글 재전송에서 중복 판정/색인을 실행하면 안 된다")
    monkeypatch.setattr("runner.make_issue_tools", unexpected)
    result = await run_publish_batch(PublishBatchRequest(batch_id="b", tickets=[source(comment_only=True)]),
                                     Settings(rag_enabled=True), publisher=CommentPublisher())
    assert result.items[0].status == "UPDATED"


def test_initial_update_and_retry_share_same_comment_marker():
    ticket = source(comment_only=True)
    retry = publication_preview(ticket)
    initial = publication_preview(ticket.model_copy(update={"retry_comment_only": False}))
    assert initial["secondaryBody"] == retry["secondaryBody"]


@pytest.mark.anyio
async def test_unparseable_write_response_is_unknown():
    class BadResponse:
        async def ainvoke(self, args):
            return "connection closed without a JSON response"
    result = await publish_one(source(), McpPublisher(BadResponse()))
    assert result.status == "UNKNOWN"


def test_backend_camel_case_comment_request_runs_via_agent_api_and_reports(monkeypatch):
    import time
    import main
    from fastapi.testclient import TestClient
    from conftest import FakeReporter

    publisher, reporter = CommentPublisher(), FakeReporter()
    monkeypatch.setattr(main, "_publish_jobs", {})
    monkeypatch.setattr(main.app.state, "overrides", {"settings": Settings(rag_enabled=True),
                        "batch_publisher": publisher, "reporter": reporter})
    payload = PublishBatchRequest(batch_id="comment-api", tickets=[source(comment_only=True)]).model_dump(by_alias=True, mode="json")
    assert payload["tickets"][0]["retryCommentOnly"] is True
    with TestClient(main.app) as client:
        assert client.post("/publish", json=payload).status_code == 202
        for _ in range(100):
            result = main._publish_jobs["comment-api"]
            if result["status"] != "RUNNING":
                break
            time.sleep(.01)
        assert result["items"][0]["status"] == "UPDATED"
        assert reporter.publish_results[0]["items"][0]["status"] == "UPDATED"
        client.post("/publish", json=payload)
        assert len(publisher.writes) == 1
