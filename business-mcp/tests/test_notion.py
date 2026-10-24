"""Notion REST 래퍼와 Markdown 블록 변환 테스트. 모든 호출은 MockTransport로 처리한다."""

import json

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from integrations.notion import api as notion
from integrations.notion import blocks


ROOT_ID = "11111111-1111-1111-1111-111111111111"
DOC_ID = "22222222-2222-2222-2222-222222222222"
FOLDER_ID = "33333333-3333-3333-3333-333333333333"


@pytest.fixture
def client_with(monkeypatch):
    monkeypatch.setenv("NOTION_TOKEN", "test-token")

    def make(handler):
        return notion.make_client(transport=httpx.MockTransport(handler))

    return make


def page(page_id=ROOT_ID, title="기획 문서", url="https://www.notion.so/plan"):
    return {"id": page_id, "url": url, "properties": {"제목": {"type": "title", "title": [{"plain_text": title}]}}}


def text_block(kind, text, **extra):
    return {"type": kind, kind: {"rich_text": [{"plain_text": text}], **extra}}


def test_normalize_page_id_accepts_url_and_rejects_invalid():
    assert notion.normalize_page_id(f"https://www.notion.so/team-plan-{ROOT_ID.replace('-', '')}") == ROOT_ID
    assert notion.normalize_page_id(ROOT_ID.replace("-", "")) == ROOT_ID
    with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
        notion.normalize_page_id("https://www.notion.so/not-a-page")


@pytest.mark.parametrize("value", [
    "https://app.notion.com/p/YUNK-EVAL-20261024-1233-12345678123456789abcdef012345678",
    "https://www.notion.so/20261024-1233-12345678-1234-5678-9abc-def012345678?source=copy_link",
    "https://app.notion.com/p/12345678123456789abcdef012345678?v=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    "https://team.notion.site/%EC%A0%95%EC%82%B0-12345678123456789ABCDEF012345678/",
    "12345678-1234-5678-9ABC-DEF012345678",
])
def test_normalize_page_id_uses_complete_path_suffix_not_title_query_or_fragment(value):
    assert notion.normalize_page_id(value) == "12345678-1234-5678-9abc-def012345678"


@pytest.mark.parametrize("value", [
    "https://www.notion.so/not-a-page?v=" + ROOT_ID.replace('-', ''),
    "https://www.notion.so/not-a-page#" + ROOT_ID.replace('-', ''),
    ROOT_ID.replace('-', '') + "a",
    "11111111-11111111-11111111-11111111",
    "text " + ROOT_ID + " text",
])
def test_normalize_page_id_rejects_partial_ids_and_non_page_query_ids(value):
    with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
        notion.normalize_page_id(value)


def test_check_page_numeric_title_uses_actual_page_endpoint(client_with):
    actual = "12345678-1234-5678-9abc-def012345678"
    def handler(request):
        assert request.url.path == f"/v1/pages/{actual}"
        return httpx.Response(200, json=page(actual))
    with client_with(handler) as client:
        result = notion.check_notion_page(client, "https://app.notion.com/p/YUNK-EVAL-20261024-1233-" + actual.replace('-', ''))
    assert result['page_id'] == actual


def test_check_page_sends_token_and_extracts_title(client_with):
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        seen["auth"] = request.headers["authorization"]
        seen["version"] = request.headers["notion-version"]
        return httpx.Response(200, json=page())

    with client_with(handler) as client:
        out = notion.check_notion_page(client, ROOT_ID)
    assert seen == {"path": f"/v1/pages/{ROOT_ID}", "auth": "Bearer test-token", "version": notion.NOTION_VERSION}
    assert out == {"page_id": ROOT_ID, "title": "기획 문서", "url": "https://www.notion.so/plan"}


def test_list_pages_excludes_generated_ticket_folder(client_with):
    def handler(request):
        if request.url.path == f"/v1/pages/{ROOT_ID}":
            return httpx.Response(200, json=page())
        assert request.url.path == f"/v1/blocks/{ROOT_ID}/children"
        return httpx.Response(200, json={"results": [
            {"id": DOC_ID, "type": "child_page", "child_page": {"title": "요구사항"}},
            {"id": FOLDER_ID, "type": "child_page", "child_page": {"title": notion.TICKET_FOLDER_TITLE}},
        ], "has_more": False})

    with client_with(handler) as client:
        assert notion.list_pages(client, ROOT_ID) == [{"page_id": DOC_ID, "title": "요구사항"}]


def test_read_page_returns_markdown_and_truncation(client_with):
    def handler(request):
        if request.url.path == f"/v1/pages/{DOC_ID}":
            return httpx.Response(200, json=page(DOC_ID, "요구사항"))
        return httpx.Response(200, json={"results": [
            text_block("heading_1", "로그인"), text_block("paragraph", "사용자는 로그인한다."),
            text_block("to_do", "테스트", checked=True),
        ], "has_more": False})

    with client_with(handler) as client:
        out = notion.read_page(client, DOC_ID, max_chars=10)
    assert out["title"] == "요구사항"
    assert out["markdown"] == "# 로그인\n\n사용자"
    assert out["truncated"] is True


def test_create_page_reuses_ticket_folder_and_appends_remaining_blocks(client_with):
    calls = []

    def handler(request):
        body = json.loads(request.content) if request.content else None
        calls.append((request.method, request.url.path, body))
        if request.method == "GET" and request.url.path == f"/v1/pages/{ROOT_ID}":
            return httpx.Response(200, json=page())
        if request.method == "GET":
            return httpx.Response(200, json={"results": [
                {"id": FOLDER_ID, "type": "child_page", "child_page": {"title": notion.TICKET_FOLDER_TITLE}},
            ], "has_more": False})
        if request.method == "POST":
            return httpx.Response(200, json=page("44444444-4444-4444-4444-444444444444", "새 티켓", "page-url"))
        if request.method == "PATCH":
            return httpx.Response(200, json={})
        raise AssertionError("unexpected request")

    # 101개 Markdown 목록은 첫 생성 100개 + PATCH 1개여야 한다.
    markdown = "\n".join(f"- 항목 {index}" for index in range(101))
    with client_with(handler) as client:
        out = notion.create_page(client, ROOT_ID, " 새 티켓 ", markdown)
    post = next(item for item in calls if item[0] == "POST")
    patch = next(item for item in calls if item[0] == "PATCH")
    assert post[2]["parent"] == {"type": "page_id", "page_id": FOLDER_ID}
    assert post[2]["properties"]["title"]["title"][0]["text"]["content"] == "새 티켓"
    assert len(post[2]["children"]) == 100
    assert patch[1] == "/v1/blocks/44444444-4444-4444-4444-444444444444/children"
    assert len(patch[2]["children"]) == 1
    assert out == {"status": "created", "page_id": "44444444-4444-4444-4444-444444444444", "url": "page-url", "folder_page_id": FOLDER_ID}


def test_create_page_creates_ticket_folder_when_missing(client_with):
    parents = []

    def handler(request):
        body = json.loads(request.content) if request.content else None
        if request.method == "GET" and request.url.path == f"/v1/pages/{ROOT_ID}":
            return httpx.Response(200, json=page())
        if request.method == "GET":
            return httpx.Response(200, json={"results": [], "has_more": False})
        parents.append(body["parent"]["page_id"])
        if len(parents) == 1:
            return httpx.Response(200, json=page(FOLDER_ID, notion.TICKET_FOLDER_TITLE))
        return httpx.Response(200, json=page("44444444-4444-4444-4444-444444444444", "티켓", "url"))

    with client_with(handler) as client:
        out = notion.create_page(client, ROOT_ID, "티켓", "본문")
    assert parents == [ROOT_ID, FOLDER_ID]
    assert out["folder_page_id"] == FOLDER_ID


def test_blocks_conversion_and_errors(client_with, monkeypatch):
    converted = blocks.markdown_to_blocks("# 제목\n- [ ] 할 일\n> 근거\n```python\nprint(1)\n```")
    assert [block["type"] for block in converted] == ["heading_1", "to_do", "quote", "code"]
    assert blocks.blocks_to_markdown(converted) == "# 제목\n\n- [ ] 할 일\n\n> 근거\n\n```python\nprint(1)\n```"

    with client_with(lambda request: httpx.Response(403, json={"message": "forbidden"})) as client:
        with pytest.raises(ToolError, match=r"^\[NOTION_FORBIDDEN\]"):
            notion.check_notion_page(client, ROOT_ID)
    with client_with(lambda request: httpx.Response(500, json={"message": "bad"})) as client:
        with pytest.raises(ToolError, match=r"^\[NOTION_ERROR\]"):
            notion.read_page(client, ROOT_ID)
    monkeypatch.delenv("NOTION_TOKEN")
    with pytest.raises(ToolError, match=r"^\[NOTION_CONFIG_MISSING\]"):
        notion.make_client()
