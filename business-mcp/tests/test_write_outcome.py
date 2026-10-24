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


ROOT = "11111111-1111-1111-1111-111111111111"
FOLDER = "22222222-2222-2222-2222-222222222222"
LONG_MARKDOWN = "\n\n".join(f"문단 {i}" for i in range(101))


def _notion_create(append, archive=lambda request: httpx.Response(200, json={"id": "new-page", "archived": True})):
    """루트/폴더 조회와 페이지 생성은 성공시키고, 남은 본문 추가와 보관 처리 응답만 바꿔 create_page를 부른다."""
    writes = []
    def handler(request):
        if request.method == "GET" and request.url.path.endswith("/children"):
            return httpx.Response(200, json={"results": [{"id": FOLDER, "type": "child_page", "child_page": {"title": notion.TICKET_FOLDER_TITLE}}], "has_more": False})
        if request.method == "GET":
            return httpx.Response(200, json={"id": ROOT})
        writes.append((request.method, request.url.path))
        if request.method == "POST":
            return httpx.Response(200, json={"id": "new-page", "url": "https://app.notion.com/p/new-page"})
        if request.url.path.endswith("/children"):
            return append(request)
        return archive(request)
    with httpx.Client(base_url=notion.API_BASE, transport=httpx.MockTransport(handler)) as client:
        try:
            return notion.create_page(client, ROOT, "티켓", LONG_MARKDOWN), writes
        except ToolError as exc:
            return exc, writes


@pytest.fixture(autouse=True)
def sleeps(monkeypatch):
    """429 재시도 대기를 실제로 기다리지 않고 기록만 한다."""
    calls = []
    monkeypatch.setattr(notion, "_sleep", calls.append)
    return calls


@pytest.mark.parametrize("status,code", [(400, "NOTION_VALIDATION"), (403, "NOTION_FORBIDDEN")])
def test_notion_definite_append_rejection_archives_page_and_fails(status, code):
    # 4xx는 본문 추가가 반영되지 않은 것이 확실하다. 잘린 페이지를 남기지 않고 원래 오류로 실패시켜 재시도가 중복되지 않게 한다.
    error, writes = _notion_create(lambda request: httpx.Response(status, json={"message": "rejected"}))
    assert isinstance(error, ToolError) and str(error).startswith(f"[{code}]")
    assert "https://app.notion.com/p/new-page" in str(error) and "보관 처리했습니다" in str(error)
    assert writes == [("POST", "/v1/pages"), ("PATCH", "/v1/blocks/new-page/children"), ("PATCH", "/v1/pages/new-page")]


def test_notion_archive_request_marks_page_archived():
    bodies = []
    def archive(request):
        bodies.append(request.content)
        return httpx.Response(200, json={"id": "new-page", "archived": True})
    _notion_create(lambda request: httpx.Response(400, json={"message": "bad block"}), archive)
    assert bodies == [b'{"archived":true}']


@pytest.mark.parametrize("append", [
    lambda request: httpx.Response(502, json={"message": "bad gateway"}),
    lambda request: (_ for _ in ()).throw(httpx.ReadTimeout("timeout")),
    lambda request: (_ for _ in ()).throw(httpx.ReadError("connection reset")),
])
def test_notion_ambiguous_append_failure_keeps_result_unknown(append):
    # 반영됐는지 모르는 실패는 페이지를 지우지 않고 URL과 함께 확인 대상으로 남긴다.
    error, writes = _notion_create(append)
    assert isinstance(error, ToolError) and str(error).startswith("[RESULT_UNKNOWN]")
    assert "페이지 생성 후" in str(error) and "https://app.notion.com/p/new-page" in str(error)
    assert writes == [("POST", "/v1/pages"), ("PATCH", "/v1/blocks/new-page/children")]


def test_notion_archive_failure_after_definite_rejection_requires_confirmation():
    error, writes = _notion_create(lambda request: httpx.Response(400, json={"message": "bad block"}),
                                   lambda request: httpx.Response(500, json={"message": "down"}))
    assert isinstance(error, ToolError) and str(error).startswith("[RESULT_UNKNOWN]")
    assert "보관 처리하지 못했습니다" in str(error) and "https://app.notion.com/p/new-page" in str(error)
    assert len(writes) == 3


def _sequence(*responses):
    """호출 순서대로 미리 정한 응답을 돌려주는 핸들러."""
    queue = list(responses)
    return lambda request: queue.pop(0)


def _rate_limited(retry_after="1"):
    return httpx.Response(429, headers={"Retry-After": retry_after}, json={"message": "rate limited"})


APPEND = ("PATCH", "/v1/blocks/new-page/children")
ARCHIVE = ("PATCH", "/v1/pages/new-page")


def test_notion_append_rate_limit_then_success_retries_append(sleeps):
    # 429는 Retry-After만큼 기다렸다가 본문 추가 자체를 다시 시도한다. 성공하면 보관 처리 없이 생성 완료다.
    result, writes = _notion_create(_sequence(_rate_limited("2"), httpx.Response(200, json={"results": []})))
    assert result["status"] == "created" and result["url"] == "https://app.notion.com/p/new-page"
    assert writes == [("POST", "/v1/pages"), APPEND, APPEND]
    assert sleeps == [2.0]


def test_notion_persistent_append_rate_limit_archives_page_and_fails(sleeps):
    error, writes = _notion_create(lambda request: _rate_limited())
    assert isinstance(error, ToolError) and str(error).startswith("[NOTION_RATE_LIMIT]")
    assert "https://app.notion.com/p/new-page" in str(error) and "보관 처리했습니다" in str(error)
    assert writes == [("POST", "/v1/pages")] + [APPEND] * notion.RATE_LIMIT_ATTEMPTS + [ARCHIVE]
    assert len(sleeps) == notion.RATE_LIMIT_ATTEMPTS - 1


def test_notion_persistent_rate_limit_with_archive_rate_limited_requires_confirmation(sleeps):
    # 보관 처리도 429면 Retry-After를 한 번 따른 뒤 다시 시도하고, 그래도 실패하면 페이지가 남으므로 확인 대상이다.
    error, writes = _notion_create(lambda request: _rate_limited(), lambda request: _rate_limited())
    assert isinstance(error, ToolError) and str(error).startswith("[RESULT_UNKNOWN]")
    assert "보관 처리하지 못했습니다" in str(error) and "https://app.notion.com/p/new-page" in str(error)
    assert writes == [("POST", "/v1/pages")] + [APPEND] * notion.RATE_LIMIT_ATTEMPTS + [ARCHIVE, ARCHIVE]
    assert len(sleeps) == notion.RATE_LIMIT_ATTEMPTS


def test_notion_archive_rate_limit_then_success_after_rejection(sleeps):
    error, writes = _notion_create(lambda request: httpx.Response(400, json={"message": "bad block"}),
                                   _sequence(_rate_limited("3"), httpx.Response(200, json={"id": "new-page", "archived": True})))
    assert isinstance(error, ToolError) and str(error).startswith("[NOTION_VALIDATION]")
    assert "보관 처리했습니다" in str(error)
    assert writes == [("POST", "/v1/pages"), APPEND, ARCHIVE, ARCHIVE]
    assert sleeps == [3.0]


@pytest.mark.parametrize("header,expected", [("120", notion.RETRY_AFTER_CAP_SECONDS), ("-5", 0.0), ("soon", notion.RETRY_AFTER_DEFAULT_SECONDS)])
def test_notion_retry_after_wait_is_capped(sleeps, header, expected):
    _notion_create(_sequence(_rate_limited(header), httpx.Response(200, json={"results": []})))
    assert sleeps == [expected]
