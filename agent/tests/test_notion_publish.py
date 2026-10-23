import pytest

from batch_publish import publish_batch
from publisher import McpPublisher, ToolCallError
from runner import make_mcp_publisher
from schemas import Project, PublishBatchRequest, PublishTicket

ROOT = "https://www.notion.so/root-page"


class NotionPublisher:
    def __init__(self, fail=None):
        self.calls = []
        self.fail = fail or {}

    async def create_issue(self, **args):
        self.calls.append(("create_issue", args))
        return {"number": 1, "url": "issue-url"}

    async def create_page(self, **args):
        self.calls.append(("create_page", args))
        error = self.fail.get(args["title"])
        if error:
            raise error
        return {"status": "created", "page_id": f"page-{args['title']}", "url": f"https://notion.so/{args['title']}"}


def ticket(ticket_id="a", title="디자인 시안", root=ROOT):
    return PublishTicket(
        ticket_id=ticket_id, project=Project(github_owner="o", github_repo="r", notion_page_url=root), meeting_id=1,
        meeting_title="주간 회의", title=title,
        content_json={"description": "시안을 만든다", "tasks": ["와이어프레임"], "assignees": {"names": ["김디자인"]}},
        processing_type="CREATE", review_status="CONFIRMED", publish_target="NOTION",
    )


async def run(tickets, publisher):
    return await publish_batch(PublishBatchRequest(batch_id="b", tickets=tickets), publisher)


@pytest.mark.anyio
async def test_notion_target_creates_page_under_project_root_and_never_creates_issue():
    publisher = NotionPublisher()
    out = await run([ticket()], publisher)

    assert [c[0] for c in publisher.calls] == ["create_page"]
    args = publisher.calls[0][1]
    assert args["root_page_url_or_id"] == ROOT and args["title"] == "디자인 시안"
    assert "## 작업 내용\n시안을 만든다" in args["markdown"] and "- [ ] 와이어프레임" in args["markdown"]
    item = out.items[0]
    assert item.status == "PUBLISHED" and item.processing_type == "CREATE"
    assert item.notion_page_id == "page-디자인 시안" and item.notion_page_url == "https://notion.so/디자인 시안"
    assert item.github_issue_number is None and item.github_issue_url is None
    dumped = out.model_dump(by_alias=True)["items"][0]
    assert dumped["notionPageId"] and dumped["notionPageUrl"]
    assert out.status == "SUCCEEDED"


@pytest.mark.anyio
@pytest.mark.parametrize("root", [None, "", "   "])
async def test_missing_notion_root_fails_without_calling_notion_or_github(root):
    publisher = NotionPublisher()
    out = await run([ticket(root=root)], publisher)

    item = out.items[0]
    assert item.status == "FAILED" and item.error_code == "NOTION_ROOT_MISSING" and item.retryable is False
    assert publisher.calls == []
    assert out.status == "FAILED"


@pytest.mark.anyio
@pytest.mark.parametrize("code, retryable", [
    ("NOTION_FORBIDDEN", False), ("NOTION_NOT_FOUND", False), ("NOTION_AUTH", False),
    ("NOTION_RATE_LIMIT", True), ("NOTION_ERROR", True),
])
async def test_notion_tool_errors_are_failed_and_retryable_only_when_transient(code, retryable):
    publisher = NotionPublisher(fail={"디자인 시안": ToolCallError(code, f"[{code}] 실패")})
    out = await run([ticket()], publisher)

    item = out.items[0]
    assert item.status == "FAILED" and item.error_code == code and item.retryable is retryable
    assert [c[0] for c in publisher.calls] == ["create_page"]


@pytest.mark.anyio
async def test_notion_timeout_is_unknown_and_retryable():
    publisher = NotionPublisher(fail={"디자인 시안": ToolCallError("PUBLISH_FAILED", "timed out", ambiguous=True)})
    out = await run([ticket()], publisher)

    assert out.items[0].status == "UNKNOWN" and out.items[0].retryable is True


@pytest.mark.anyio
async def test_page_without_url_is_reported_as_unknown_not_published():
    class NoUrl(NotionPublisher):
        async def create_page(self, **args):
            return {"status": "created", "page_id": "p"}

    out = await run([ticket()], NoUrl())

    assert out.items[0].status == "UNKNOWN"


@pytest.mark.anyio
async def test_partial_failure_keeps_other_pages_and_marks_batch_partial():
    publisher = NotionPublisher(fail={"둘째": ToolCallError("NOTION_FORBIDDEN", "[NOTION_FORBIDDEN] 공유 안 됨")})
    out = await run([ticket("a", "첫째"), ticket("b", "둘째"), ticket("c", "셋째")], publisher)

    assert [i.status for i in out.items] == ["PUBLISHED", "FAILED", "PUBLISHED"]
    assert out.status == "PARTIAL_FAILED" and out.summary == {"PUBLISHED": 2, "FAILED": 1}
    assert [c[1]["title"] for c in publisher.calls] == ["첫째", "둘째", "셋째"]


@pytest.mark.anyio
async def test_mcp_publisher_wires_create_page_tool():
    class Tool:
        name = "create_page"

        def __init__(self, name):
            self.name = name
            self.calls = []

        async def ainvoke(self, args):
            self.calls.append(args)
            return '{"status": "created", "page_id": "p1", "url": "u1"}'

    tools = {name: Tool(name) for name in ("create_issue", "create_page")}
    publisher = make_mcp_publisher(tools)

    assert isinstance(publisher, McpPublisher) and publisher.page_tool is tools["create_page"]
    out = await run([ticket()], publisher)
    assert out.items[0].notion_page_url == "u1" and tools["create_issue"].calls == []


@pytest.mark.anyio
async def test_missing_create_page_tool_fails_with_mcp_unavailable():
    out = await run([ticket()], McpPublisher(create_issue_tool=object()))

    assert out.items[0].status == "FAILED" and out.items[0].error_code == "MCP_UNAVAILABLE"


@pytest.mark.anyio
async def test_dry_run_publisher_accepts_notion_target():
    from publisher import DryRunPublisher

    publisher = DryRunPublisher()
    out = await run([ticket()], publisher)

    assert out.items[0].status == "PUBLISHED" and out.items[0].notion_page_url is None
    assert publisher.calls[0]["root_page_url_or_id"] == ROOT
