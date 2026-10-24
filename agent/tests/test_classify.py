"""발행 대상 분류와 Slack 공지·Notion 페이지 출력문 (TASK-020, TASK-021)."""

import uuid
from dataclasses import replace

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from publisher import build_notion_page, build_slack_notice
from runner import run
from schemas import DecisionOut
from test_dedupe import FakeToolChat, ToolLLM
from test_issue_tools import make_tools

pytestmark = pytest.mark.anyio


async def run_case(settings, llm=None, request=None, issue_tools=None, publisher=None):
    return await run(request or make_request(), settings, llm=llm or FakeLLM(), publisher=publisher or FakePublisher(),
                     reporter=FakeReporter(), issue_tools=issue_tools)


def by_key(result):
    return {i.work_key: i for i in result.items}


async def test_todos_are_classified_with_reason(draft_settings):
    llm = FakeLLM(notion_for={"10m1-a5"})
    result = await run_case(draft_settings, llm=llm)
    items = by_key(result)
    notion = items["10m1-a5"].content_json
    assert notion["publish_target"] == "NOTION" and notion["target_reason"] == "코드 변경 없는 조사"
    # Notion 티켓은 Notion 페이지 출력문으로 만들고, GitHub 전용 값(라벨, 이슈 본문)은 없다
    assert notion["issue_body"] is None and notion["labels"] == []
    assert notion["notion_page"]["title"] == items["10m1-a5"].title
    assert notion["notion_page"]["markdown"].startswith("## 작업 내용\n")
    github = items["10m1-a1"].content_json
    assert github["publish_target"] == "GITHUB" and github["target_reason"] == "코드 변경이 필요한 구현"
    assert github["issue_body"].startswith("## 배경\n") and github["notion_page"] is None


async def test_one_slack_notice_per_meeting(draft_settings):
    result = await run_case(draft_settings, llm=FakeLLM(notion_for={"10m1-a5"}))
    notices = [i for i in result.items if i.content_json["publish_target"] == "SLACK"]
    assert len(notices) == 1
    notice = notices[0]
    assert notice.work_key == "10min_1-notice"
    assert notice.title == f"회의 결과 공지: {make_request().meeting.title}"
    assert notice.review_status == "PENDING" and notice.publish_status == "DRAFT"
    # 같은 작업이면 같은 ID라서 다시 보고해도 공지가 두 개 저장되지 않는다
    assert notice.task_item_id == str(uuid.uuid5(uuid.NAMESPACE_URL, f"meet-to-ticket:{result.task_id}:slack-notice"))
    text = notice.content_json["slack"]["text"]
    assert text == notice.content_json["description"]
    assert "*결정 사항*" in text and "• 1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다." in text
    assert "*후속 작업*" in text
    # 담당자 1명인 티켓은 @이름으로 후속 작업을 확인하고, 여러 명인 티켓은 담당 확인 필요로 남긴다.
    reviewers = notice.content_json["slack"]["reviewers"]
    assert reviewers and all(f"• @{r['name']}" in text for r in reviewers)
    assert "• 담당 확인 필요" in text
    # 받는 사람은 업로더다 (백엔드가 정함). 공지 자체에는 담당자가 없다
    assert notice.content_json["assignees"]["names"] == []


async def test_meeting_with_decisions_only_still_gets_notice(draft_settings):
    text = "# 결정만 있는 회의\n\n## [구간 1] 정리\n\n로그는 30일 보관하기로 했다.\n"
    req = make_request(content=text)
    llm = FakeLLM(decisions=[DecisionOut(content="로그는 30일 보관한다", evidence_quotes=["로그는 30일 보관하기로 했다."])])
    result = await run_case(draft_settings, llm=llm, request=req)
    assert result.status == "SUCCEEDED" and result.error_code == "NO_TICKETS"
    assert [i.content_json["publish_target"] for i in result.items] == ["SLACK"]
    assert "• 로그는 30일 보관한다" in result.items[0].content_json["slack"]["text"]


async def test_dedupe_judges_github_tickets_only(draft_settings):
    chat = FakeToolChat()
    result = await run_case(draft_settings, llm=ToolLLM(chat, notion_for={"10m1-a5"}), issue_tools=make_tools()[0])
    items = by_key(result)
    assert items["10m1-a5"].content_json["dedupe"] is None  # Notion 티켓은 대조하지 않는다 (기획 1.7.2)
    assert items["10m1-a1"].content_json["dedupe"] is not None
    assert result.metrics["dedupe"]["judged"] == 7


async def test_phase1_flow_publishes_github_only(settings):
    pub = FakePublisher()
    result = await run_case(settings, llm=FakeLLM(notion_for={"10m1-a5"}), publisher=pub)
    # PUBLISH_ON_RUN(baseline)은 분류 결과와 상관없이 GitHub로만 발행하고 공지를 만들지 않는다
    assert all(i.content_json["publish_target"] == "GITHUB" for i in result.items)
    assert by_key(result)["10m1-a5"].content_json["issue_body"]
    assert len(pub.calls) == result.summary["published"]


def test_notion_page_has_no_github_only_parts():
    ticket = {"work_key": "k", "title": "경쟁사 내보내기 조사", "action_text": "조사", "description": "설명",
              "tasks": ["자료 모으기"], "acceptance_criteria": ["정리 문서 공유"], "evidence_quotes": ["근거 문장"],
              "assignee_names": ["임나윤"], "assignees": ["jadoolim"], "labels": ["docs"],
              "due": {"text": "금요일", "date": "2026-10-24", "time": None},
              "expected": {"raw": "기존 이슈 갱신 (IAM-48)", "kind": "update", "keys": ["IAM-48"]},
              "open_questions": ["범위는?"]}
    page = build_notion_page(ticket, "m1", "배경")
    md = page["markdown"]
    order = ["## 작업 내용", "## 상세 작업", "## 완료 조건", "## 근거", "## 담당", "## 기한", "## 확인 필요", "## 회의 배경"]
    assert [md.index(h) for h in order] == sorted(md.index(h) for h in order)
    assert page["title"] == "경쟁사 내보내기 조사"
    assert "## 담당\n임나윤" in md and "@" not in md and "IAM-48" not in md and "docs" not in md


def test_slack_notice_counts_linked_and_skips_empty_sections():
    tickets = [{"assignee_names": ["박서연"], "publish_target": "GITHUB"},
               {"assignee_names": ["박서연"], "publish_target": "GITHUB"},
               {"assignee_names": [], "publish_target": "NOTION"}]
    notice = build_slack_notice("CSV 점검", "2026-10-22", [], tickets, linked=1, members=["박서연"])
    text = notice["text"]
    assert text.startswith("*[회의 요약] CSV 점검* · 2026-10-22")
    assert "*결정 사항*" not in text
    assert "*후속 작업*" in text
    assert "• @박서연" in text and "담당 확인 필요" in text
    assert notice["reviewers"] == [{"name": "박서연", "tickets": 2}]


def test_slack_notice_review_requests_match_ticket_recipients():
    """검토 요청은 실제로 티켓을 받는 사람과 같다 (백엔드 AssigneeResolver와 같은 규칙)."""
    tickets = [
        {"assignee_names": ["박서연"], "publish_target": "GITHUB"},
        {"assignee_names": ["박서연"], "publish_target": "NOTION"},   # Notion은 업로더가 받는다
        {"assignee_names": ["외부인"], "publish_target": "GITHUB"},   # 프로젝트 멤버가 아님
        {"assignee_names": ["김민준"], "publish_target": "GITHUB"},   # 동명이인
        {"assignee_names": ["박서연", "박서연"], "publish_target": "GITHUB"},  # 같은 사람이 두 번
    ]
    notice = build_slack_notice("회의", None, [], tickets, linked=0, members=["박서연", "김민준", "김민준"])
    text = notice["text"]
    assert "• @박서연" in text and "@김민준" not in text and "@외부인" not in text
    assert "• 담당 확인 필요" in text
    assert notice["reviewers"] == [{"name": "박서연", "tickets": 2}]


async def test_phase1_keeps_labels_of_tickets_classified_as_notion(settings):
    """PUBLISH_ON_RUN(baseline)에서는 분류하지 않으므로 Notion으로 판단된 티켓도 라벨을 그대로 쓴다."""
    s = replace(settings, issue_labels=["feature", "docs"])
    result = await run_case(s, llm=FakeLLM(notion_for={"10m1-a5"}))
    a5 = by_key(result)["10m1-a5"].content_json
    assert a5["publish_target"] == "GITHUB" and a5["target_reason"] is None
    assert a5["labels"] == ["feature"]
