import pytest

from batch_publish import publish_batch
from runner import run_publish_batch
from schemas import Project, PublishBatchRequest, PublishTicket


class BatchPublisher:
    def __init__(self):
        self.calls = []

    async def create_issue(self, **args):
        self.calls.append(("create", args))
        return {"number": 101, "url": "issue-url", "dropped_labels": ["unknown"]}

    async def add_comment(self, **args):
        self.calls.append(("comment", args))
        return {"url": "comment-url"}

    async def update_issue(self, **args):
        self.calls.append(("update", args))
        return {"number": args["number"], "url": "issue-url", "dropped_labels": []}


def ticket(ticket_id, kind, number=None):
    return PublishTicket(
        ticket_id=ticket_id, project=Project(github_owner="o", github_repo="r"), meeting_id=1,
        meeting_title="주간 회의", title="CSV 개선", content_json={"description": "CSV를 개선한다", "labels": ["bug"]},
        processing_type=kind, target_issue_number=number, review_status="CONFIRMED",
    )


@pytest.mark.anyio
async def test_batch_runs_create_link_and_update_with_their_actual_results():
    publisher = BatchPublisher()
    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[
        ticket("a", "CREATE"), ticket("b", "LINK", 7), ticket("c", "UPDATE", 8),
    ]), publisher)

    assert [item.status for item in out.items] == ["PUBLISHED", "LINKED", "UPDATED"]
    assert out.items[0].dropped_labels == ["unknown"]
    assert out.items[1].github_issue_number == 7 and out.items[1].comment_url == "comment-url"
    assert [call[0] for call in publisher.calls] == ["create", "comment", "update"]
    assert out.status == "SUCCEEDED"


@pytest.mark.anyio
async def test_link_and_update_require_target_issue_number():
    publisher = BatchPublisher()
    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[ticket("a", "LINK")]), publisher)

    assert out.status == "FAILED"
    assert out.items[0].error_code == "INVALID_ARGUMENT"


@pytest.mark.anyio
async def test_batch_keeps_publish_success_when_post_publish_indexing_fails(settings, monkeypatch):
    """색인은 best-effort이므로 실패해도 이미 성공한 GitHub 발행 결과를 바꾸지 않는다."""
    settings.rag_enabled = True

    class FailingIndex:
        def __init__(self):
            self.calls = []

        async def index(self, number):
            self.calls.append(number)
            return False

    index = FailingIndex()

    async def fake_make_issue_tools(*_):
        return index, "ready"

    async def fake_recheck(tickets, **_):
        return {"tickets": tickets, "sync": None, "skipped": None}

    monkeypatch.setattr("runner.make_issue_tools", fake_make_issue_tools)
    monkeypatch.setattr("recheck.recheck", fake_recheck)

    out = await run_publish_batch(
        PublishBatchRequest(batch_id="batch-1", tickets=[ticket("a", "CREATE")]),
        settings,
        publisher=BatchPublisher(),
        llm=object(),
    )

    assert out.status == "SUCCEEDED"
    assert out.items[0].status == "PUBLISHED"
    assert index.calls == [101]


@pytest.mark.anyio
async def test_unexpected_error_fails_only_that_ticket_and_keeps_earlier_results():
    """한 티켓의 예상하지 못한 예외가 앞서 발행된 결과를 지우지 않아야 재요청 때 중복 발행되지 않는다."""
    publisher = BatchPublisher()
    bad = ticket("b", "LINK", 7)
    bad.content_json["evidence_quotes"] = [123]
    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[
        ticket("a", "CREATE"), bad, ticket("c", "UPDATE", 8),
    ]), publisher)

    assert [item.status for item in out.items] == ["PUBLISHED", "FAILED", "UPDATED"]
    assert out.items[1].error_code == "PUBLISH_FAILED"
    assert out.status == "PARTIAL_FAILED"


@pytest.mark.anyio
async def test_unexpected_error_after_request_is_reported_as_unknown():
    class BrokenResult(BatchPublisher):
        async def create_issue(self, **args):
            self.calls.append(("create", args))
            return ["not-a-dict"]

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[ticket("a", "CREATE")]), BrokenResult())

    assert out.items[0].status == "UNKNOWN" and out.items[0].retryable


@pytest.mark.anyio
async def test_dry_run_publisher_handles_link_and_update():
    from publisher import DryRunPublisher

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[
        ticket("a", "CREATE"), ticket("b", "LINK", 7), ticket("c", "UPDATE", 8),
    ]), DryRunPublisher())

    assert [item.status for item in out.items] == ["PUBLISHED", "LINKED", "UPDATED"]
    assert out.status == "SUCCEEDED"
