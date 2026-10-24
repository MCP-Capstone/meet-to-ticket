import pytest

from batch_publish import _ticket, publish_batch
from publisher import ToolCallError
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
        # business-mcp post_message의 실제 응답 모양 (business-mcp/integrations/slack/api.py)
        return {"status": "posted", "channel_id": args["channel_id"], "timestamp": "1.2",
                "permalink": "slack-url", "mentioned_user_ids": []}


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
async def test_slack_uses_saved_description_before_legacy_notice_text():
    publisher = BatchPublisher()
    notice = ticket("notice", "CREATE")
    notice.project = Project(github_owner="o", github_repo="r", slack_channel_id="C123")
    notice.publish_target = "SLACK"
    notice.content_json = {
        "description": "담당자가 저장한 최종 공지",
        "slack": {"text": "수정 전 공지", "reviewers": []},
    }

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[notice]), publisher)

    assert out.items[0].status == "POSTED"
    assert publisher.calls[0][1]["text"] == "담당자가 저장한 최종 공지"


@pytest.mark.anyio
async def test_slack_retry_after_unknown_outcome_does_not_post_again():
    """post_message는 비멱등이라 결과 모름 뒤 재발행은 같은 공지를 다시 올리지 않는다 (#278)."""
    publisher = BatchPublisher()
    notice = ticket("notice", "CREATE")
    notice.project = Project(github_owner="o", github_repo="r", slack_channel_id="C123")
    notice.publish_target = "SLACK"
    notice.content_json = {"slack": {"text": "회의 공지", "reviewers": []}}
    notice.previous_outcome_unknown = True

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[notice]), publisher)

    assert publisher.calls == []
    item = out.items[0]
    assert item.status == "FAILED" and item.error_code == "SLACK_VERIFY_REQUIRED" and item.retryable is False


@pytest.mark.anyio
async def test_slack_config_error_is_reported_before_unknown_outcome_block():
    """설정 오류는 확실하므로 결과 모름 차단보다 먼저 알린다 (#280)."""
    publisher = BatchPublisher()
    notice = ticket("notice", "CREATE")
    notice.publish_target = "SLACK"
    notice.content_json = {"slack": {"text": "회의 공지", "reviewers": []}}
    notice.previous_outcome_unknown = True

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[notice]), publisher)

    assert publisher.calls == [] and out.items[0].error_code == "INVALID_ARGUMENT"


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
async def test_recheck_only_receives_github_tickets(settings, monkeypatch):
    """Slack·Notion 티켓은 기존 GitHub 이슈와 대조하지 않는다. 재대조가 모두 연결로 판단해도 GitHub 티켓만 바뀐다 (#297)."""
    settings.rag_enabled = True
    rechecked = []

    class Index:
        async def index(self, number):
            return True

    async def fake_make_issue_tools(*_):
        return Index(), "ready"

    async def link_everything(tickets, **_):
        rechecked.extend(t["work_key"] for t in tickets)
        return {"tickets": [{**t, "handling": {"type": "LINK", "issue_number": 17, "changed": True}} for t in tickets],
                "sync": None, "skipped": None}

    monkeypatch.setattr("runner.make_issue_tools", fake_make_issue_tools)
    monkeypatch.setattr("recheck.recheck", link_everything)
    notice = ticket("notice", "CREATE")
    notice.publish_target = "SLACK"
    notice.content_json = {"slack": {"text": "회의 공지", "reviewers": []}}
    page = ticket("page", "CREATE")
    page.publish_target = "NOTION"

    out = await run_publish_batch(
        PublishBatchRequest(batch_id="batch-1", tickets=[ticket("issue", "CREATE"), notice, page]),
        settings, publisher=BatchPublisher(), llm=object(),
    )

    assert rechecked == ["issue"]
    by_id = {item.ticket_id: item for item in out.items}
    assert by_id["issue"].status == "LINKED" and by_id["issue"].processing_type == "LINK"
    # 채널 ID, Notion 기획 페이지가 없어 실패해도 처리 유형은 신규 발행이다
    assert by_id["notice"].error_code == "INVALID_ARGUMENT" and by_id["notice"].processing_type == "CREATE"
    assert by_id["page"].error_code == "NOTION_ROOT_MISSING" and by_id["page"].processing_type == "CREATE"


@pytest.mark.anyio
@pytest.mark.parametrize("target", ["SLACK", "NOTION"])
async def test_non_github_failure_reports_create_even_if_stored_type_differs(target):
    """이전에 잘못 저장된 처리 유형(LINK)이 와도 Slack·Notion 결과는 CREATE로 보고한다 (#297)."""
    source = ticket("t", "LINK", 17)
    source.publish_target = target

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[source]), BatchPublisher())

    assert out.items[0].status == "FAILED" and out.items[0].processing_type == "CREATE"
    # 발행 코드가 쓰는 처리 유형에도 LINK·대상 이슈가 남지 않는다
    handling = _ticket(source)["handling"]
    assert handling["type"] == "CREATE" and handling["issue_number"] is None


@pytest.mark.anyio
async def test_slack_notion_only_batch_skips_index_sync(settings, monkeypatch):
    """GitHub 티켓이 없는 묶음은 재대조 도구를 준비하지 않아 색인 동기화(sync_issues)도 하지 않는다 (#297)."""
    settings.rag_enabled = True
    calls = []

    async def fake_make_issue_tools(*args):
        calls.append(("make_issue_tools", args))
        return None, "ready"

    async def fake_recheck(tickets, **_):
        calls.append(("recheck", tickets))
        return {"tickets": tickets, "sync": None, "skipped": None}

    monkeypatch.setattr("runner.make_issue_tools", fake_make_issue_tools)
    monkeypatch.setattr("recheck.recheck", fake_recheck)
    notice = ticket("notice", "CREATE")
    notice.project = Project(github_owner="o", github_repo="r", slack_channel_id="C123")
    notice.publish_target = "SLACK"
    notice.content_json = {"slack": {"text": "회의 공지", "reviewers": []}}
    page = ticket("page", "CREATE")
    page.publish_target = "NOTION"

    out = await run_publish_batch(
        PublishBatchRequest(batch_id="batch-1", tickets=[notice, page]),
        settings, publisher=BatchPublisher(), llm=object(),
    )

    assert calls == []
    by_id = {item.ticket_id: item for item in out.items}
    assert by_id["notice"].status == "POSTED" and by_id["notice"].processing_type == "CREATE"
    assert by_id["page"].error_code == "NOTION_ROOT_MISSING" and by_id["page"].processing_type == "CREATE"


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
async def test_dry_run_publisher_marks_all_github_writes_as_skipped():
    from publisher import DryRunPublisher

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[
        ticket("a", "CREATE"), ticket("b", "LINK", 7), ticket("c", "UPDATE", 8),
    ]), DryRunPublisher())

    assert [item.status for item in out.items] == ["SKIPPED", "SKIPPED", "SKIPPED"]
    assert all(item.github_issue_number is None and item.github_issue_url is None for item in out.items)
    assert out.status == "SUCCEEDED"


@pytest.mark.anyio
async def test_dry_run_publisher_skips_notion_and_slack_without_error():
    from publisher import DryRunPublisher

    notion = ticket("notion", "CREATE")
    notion.project = Project(github_owner="o", github_repo="r", notion_page_url="https://www.notion.so/root")
    notion.publish_target = "NOTION"
    slack = ticket("slack", "CREATE")
    slack.project = Project(github_owner="o", github_repo="r", slack_channel_id="C123")
    slack.publish_target = "SLACK"
    slack.content_json = {"slack": {"text": "회의 결과", "reviewers": []}}

    out = await publish_batch(PublishBatchRequest(batch_id="batch-1", tickets=[notion, slack]), DryRunPublisher())

    assert [item.status for item in out.items] == ["SKIPPED", "SKIPPED"]
    assert all(item.notion_page_url is None and item.message_url is None for item in out.items)
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


class BodyPublisher(RetryPublisher):
    """이슈 번호별 본문을 들고 있다가 표식 조회에 답한다."""

    def __init__(self, bodies, **kw):
        super().__init__(**kw)
        self.bodies = bodies

    async def find_marker(self, **args):
        self.calls.append(("marker", args))
        hit = args["marker"] in self.bodies.get(args["number"], "")
        return {"found": hit, "where": "body" if hit else None, "url": None, "truncated": False}


def footer_of(t, work_key="WK-1", meeting_id=None):
    return f"---\n회의: {meeting_id or t.meeting_id} \u00b7 work_key: {work_key} \u00b7 회의 후속조치 Agent가 작성\n"


@pytest.mark.anyio
async def test_unknown_create_links_issue_whose_title_was_edited_by_body_footer():
    t = unknown_ticket("a", "CREATE")
    publisher = BodyPublisher({55: "본문\n" + footer_of(t)},
                              issues=[{"number": 55, "title": "수정 전 제목", "state": "open", "url": "https://github.com/o/r/issues/55"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].status == "PUBLISHED" and out.items[0].github_issue_number == 55
    assert writes(publisher) == []


@pytest.mark.anyio
async def test_unknown_create_does_not_link_same_work_key_from_other_meeting():
    t = unknown_ticket("a", "CREATE")
    publisher = BodyPublisher({55: footer_of(t, meeting_id="other-meeting")},
                              issues=[{"number": 55, "title": "다른 제목", "state": "open", "url": "u"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].github_issue_number == 101
    assert writes(publisher) == ["create"]


@pytest.mark.anyio
async def test_unknown_create_does_not_link_work_key_with_same_prefix():
    t = unknown_ticket("a", "CREATE")
    publisher = BodyPublisher({55: footer_of(t, work_key="WK-10")},
                              issues=[{"number": 55, "title": "다른 제목", "state": "open", "url": "u"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert writes(publisher) == ["create"]


@pytest.mark.anyio
async def test_unknown_create_ignores_footer_text_found_only_in_comment():
    t = unknown_ticket("a", "CREATE")
    publisher = RetryPublisher(issues=[{"number": 55, "title": "다른 제목", "state": "open", "url": "u"}], marker_found=True)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert writes(publisher) == ["create"]


@pytest.mark.anyio
async def test_unknown_create_legacy_issue_without_footer_still_matches_by_title_only():
    t = unknown_ticket("a", "CREATE")
    publisher = BodyPublisher({55: "표식 없는 과거 본문"},
                              issues=[{"number": 55, "title": "CSV 개선", "state": "open", "url": "u"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].github_issue_number == 55 and writes(publisher) == []
    assert not [c for c in publisher.calls if c[0] == "marker"]


class ListingPublisher(BodyPublisher):
    """검색 색인에 아직 없는 이슈를 최근 이슈 목록으로만 돌려준다 (#336)."""

    def __init__(self, bodies, recent, list_error=None, **kw):
        super().__init__(bodies, **kw)
        self.recent = recent
        self.list_error = list_error

    async def list_issues(self, **args):
        self.calls.append(("list", args))
        if self.list_error:
            raise self.list_error
        return {"issues": self.recent}


@pytest.mark.anyio
async def test_unknown_create_finds_issue_missing_from_search_in_recent_list():
    t = unknown_ticket("a", "CREATE")
    publisher = ListingPublisher({55: "본문\n" + footer_of(t)},
                                 recent=[{"number": 9, "title": "다른 작업", "state": "open", "url": "u9"},
                                         {"number": 55, "title": "CSV 개선", "state": "open", "url": "https://github.com/o/r/issues/55"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].status == "PUBLISHED" and out.items[0].github_issue_number == 55
    assert writes(publisher) == []
    assert [c[1]["state"] for c in publisher.calls if c[0] == "list"] == ["open"]
    # 제목이 같은 이슈의 끝줄을 먼저 확인한다
    assert [c[1]["number"] for c in publisher.calls if c[0] == "marker"] == [55]


@pytest.mark.anyio
async def test_unknown_create_does_not_take_same_title_issue_of_other_meeting_from_recent_list():
    t = unknown_ticket("a", "CREATE")
    publisher = ListingPublisher({40: "본문\n" + footer_of(t, meeting_id="other-meeting")},
                                 recent=[{"number": 40, "title": "CSV 개선", "state": "open", "url": "u40"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert writes(publisher) == ["create"] and out.items[0].github_issue_number == 101


@pytest.mark.anyio
async def test_unknown_create_checks_footer_of_same_title_issue_beyond_newest_recent_issues():
    t = unknown_ticket("a", "CREATE")
    recent = [{"number": n, "title": f"작업 {n}", "state": "open", "url": "u"} for n in range(100, 80, -1)]
    recent.append({"number": 60, "title": "CSV 개선", "state": "open", "url": "u60"})
    publisher = ListingPublisher({60: "본문\n" + footer_of(t)}, recent=recent)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].github_issue_number == 60 and writes(publisher) == []


@pytest.mark.anyio
async def test_unknown_create_finds_retitled_issue_missing_from_search_by_footer_in_recent_list():
    t = unknown_ticket("a", "CREATE")
    publisher = ListingPublisher({55: "본문\n" + footer_of(t)},
                                 recent=[{"number": 56, "title": "다른 작업", "state": "open", "url": "u"},
                                         {"number": 55, "title": "수정 전 제목", "state": "open", "url": "u55"}])
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].github_issue_number == 55 and writes(publisher) == []


@pytest.mark.anyio
async def test_unknown_create_checks_footer_of_only_newest_recent_issues():
    t = unknown_ticket("a", "CREATE")
    recent = [{"number": n, "title": f"작업 {n}", "state": "open", "url": "u"} for n in range(100, 70, -1)]
    publisher = ListingPublisher({}, recent=recent)
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert writes(publisher) == ["create"] and out.items[0].github_issue_number == 101
    assert [c[1]["number"] for c in publisher.calls if c[0] == "marker"] == list(range(100, 90, -1))


@pytest.mark.anyio
async def test_unknown_create_falls_back_to_search_only_when_listing_fails():
    t = unknown_ticket("a", "CREATE")
    publisher = ListingPublisher({}, recent=[], list_error=ToolCallError("GITHUB_RATE_LIMIT", "한도 초과"))
    out = await publish_batch(PublishBatchRequest(batch_id="b", tickets=[t]), publisher)

    assert out.items[0].status == "PUBLISHED" and writes(publisher) == ["create"]


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
