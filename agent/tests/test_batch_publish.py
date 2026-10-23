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

    async def post_message(self, **args):
        self.calls.append(("message", args))
        return {"url": "slack-url"}


def ticket(ticket_id, kind, number=None):
    return PublishTicket(
        ticket_id=ticket_id, project=Project(github_owner="o", github_repo="r"), meeting_id=1,
        meeting_title="주간 회의", title="CSV 개선", content_json={"description": "CSV를 개선한다", "labels": ["bug"]},
        processing_type=kind, target_issue_number=number, review_status="CONFIRMED",
    )


@pytest.mark.anyio
@pytest.mark.parametrize("github, unresolved", [(["chosen-login"], []), ([], ["선택한 담당"])])
async def test_stored_assignee_object_is_used_in_github_request_and_body(github, unresolved):
    publisher = BatchPublisher()
    source = ticket("assigned", "CREATE")
    source.content_json.update({
        "description": "수정한 작업 내용",
        "issue_body": "수정 전 본문",
        "assignees": {"names": ["선택한 담당"], "github": github, "unresolved": unresolved},
    })

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[source]), publisher)

    assert out.items[0].status == "PUBLISHED"
    args = publisher.calls[0][1]
    assert args["assignees"] == github
    assert "## 작업 내용\n수정한 작업 내용" in args["body"]
    assert "- 회의록 담당자: 선택한 담당" in args["body"]
    assert "수정 전 본문" not in args["body"]
    if github:
        assert "- GitHub: @chosen-login" in args["body"]
    else:
        assert "- GitHub 계정 미매핑: 선택한 담당" in args["body"]


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
async def test_slack_notice_posts_once_and_mentions_only_connected_reviewers():
    publisher = BatchPublisher()
    notice = ticket("notice", "CREATE")
    notice.project = Project(github_owner="o", github_repo="r", slack_channel_id="C123")
    notice.publish_target = "SLACK"
    notice.content_json = {"slack": {"text": "@박서연 확인 부탁", "reviewers": [{"name": "박서연", "tickets": 1}]}}
    notice.slack_members = [{"name": "박서연", "slack_user_id": "U123"}, {"name": "김민준", "slack_user_id": "U456"}]

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[notice]), publisher)

    assert out.items[0].status == "POSTED" and out.items[0].message_url == "slack-url"
    assert publisher.calls == [("message", {"channel_id": "C123", "text": "@박서연 확인 부탁", "mentions": [{"name": "박서연", "slack_user_id": "U123"}]})]


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


# ---------------------------------------------------------------- 결과 모름 재발행 (#211)


class RetryPublisher(BatchPublisher):
    """이미 GitHub에 반영된 상태를 흉내 낸다."""

    def __init__(self, issues=None, marker_found=False, truncated=False):
        super().__init__()
        self.issues = issues or []
        self.marker_found = marker_found
        self.truncated = truncated

    async def search_issues(self, **args):
        self.calls.append(("search", args))
        return {"issues": self.issues}

    async def find_marker(self, **args):
        self.calls.append(("marker", args))
        return {"found": self.marker_found, "where": "comment" if self.marker_found else None,
                "url": "existing-url" if self.marker_found else None, "truncated": self.truncated}


def unknown_ticket(ticket_id, kind, number=None, **extra):
    t = ticket(ticket_id, kind, number)
    t.previous_outcome_unknown = True
    t.content_json = {**t.content_json, "work_key": "WK-1", **extra}
    return t


def writes(publisher):
    return [c[0] for c in publisher.calls if c[0] in {"create", "comment", "update"}]


@pytest.mark.anyio
async def test_unknown_create_reuses_open_issue_with_same_work_key_and_title():
    publisher = RetryPublisher(issues=[{"number": 55, "title": "CSV 개선", "state": "open", "url": "https://github.com/o/r/issues/55"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[unknown_ticket("a", "CREATE")]), publisher)

    assert out.items[0].status == "PUBLISHED"
    assert out.items[0].github_issue_number == 55 and out.items[0].github_issue_url.endswith("/55")
    assert writes(publisher) == []
    assert publisher.calls[0][1]["query"] == '"WK-1"' and publisher.calls[0][1]["state"] == "open"


@pytest.mark.anyio
async def test_unknown_create_still_creates_when_no_matching_issue():
    # 제목이 다른 이슈는 같은 티켓이 아니다
    publisher = RetryPublisher(issues=[{"number": 9, "title": "다른 작업", "state": "open", "url": "u"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[unknown_ticket("a", "CREATE")]), publisher)

    assert out.items[0].status == "PUBLISHED" and out.items[0].github_issue_number == 101
    assert writes(publisher) == ["create"]


@pytest.mark.anyio
async def test_normal_create_does_not_search():
    publisher = RetryPublisher(issues=[{"number": 55, "title": "CSV 개선", "state": "open", "url": "u"}])
    await publish_batch(PublishBatchRequest(batch_id="b", tickets=[ticket("a", "CREATE")]), publisher)

    assert [c[0] for c in publisher.calls] == ["create"]


@pytest.mark.anyio
async def test_unknown_link_skips_comment_when_marker_already_exists():
    publisher = RetryPublisher(marker_found=True)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[unknown_ticket("a", "LINK", 7)]), publisher)

    assert out.items[0].status == "LINKED" and out.items[0].comment_url == "existing-url"
    assert writes(publisher) == []
    assert "meeting=1 work_key=WK-1" in publisher.calls[0][1]["marker"]


@pytest.mark.anyio
async def test_unknown_link_comments_with_marker_when_not_yet_applied():
    publisher = RetryPublisher(marker_found=False)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[unknown_ticket("a", "LINK", 7)]), publisher)

    assert out.items[0].status == "LINKED"
    comment = next(c for c in publisher.calls if c[0] == "comment")[1]
    assert comment["body"].rstrip().endswith("<!-- meettoticket:link meeting=1 work_key=WK-1 -->")


@pytest.mark.anyio
async def test_unknown_update_skips_append_when_section_marker_already_exists():
    publisher = RetryPublisher(marker_found=True)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[unknown_ticket("a", "UPDATE", 8)]), publisher)

    assert out.items[0].status == "UPDATED" and out.items[0].github_issue_number == 8
    assert writes(publisher) == []


@pytest.mark.anyio
async def test_first_update_carries_marker_without_checking():
    publisher = RetryPublisher(marker_found=True)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[ticket("a", "UPDATE", 8)]), publisher)

    assert out.items[0].status == "UPDATED"
    update = publisher.calls[0]
    assert update[0] == "update" and "<!-- meettoticket:update meeting=1 work_key=a -->" in update[1]["append_section"]


@pytest.mark.anyio
async def test_unknown_update_fails_instead_of_writing_when_comments_not_fully_checked():
    publisher = RetryPublisher(marker_found=False, truncated=True)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[unknown_ticket("a", "UPDATE", 8)]), publisher)

    assert out.items[0].status == "FAILED" and out.items[0].error_code == "MARKER_CHECK_INCOMPLETE"
    assert writes(publisher) == []


@pytest.mark.anyio
async def test_previous_unknown_ignores_edited_flag_so_recheck_can_override():
    from batch_publish import _ticket

    t = unknown_ticket("a", "CREATE")
    t.processing_type_edited = True
    assert _ticket(t)["handling"]["edited"] is False
    t.previous_outcome_unknown = False
    assert _ticket(t)["handling"]["edited"] is True
