"""PUBLISH_ON_RUN=true여도 담당자 미확정/확인 필요 티켓은 외부에 쓰지 않는다 (#267).

실제 OpenAI/GitHub는 호출하지 않는다.
"""

import pytest

from conftest import MEMBERS, FakeLLM, FakePublisher, FakeReporter, make_state, run_plain
from config import Settings, SettingsError, validate_settings
from nodes import AgentNodes
from schemas import ProjectMember

pytestmark = pytest.mark.anyio


async def run_case(settings, state):
    pub = FakePublisher()
    nodes = AgentNodes(FakeLLM(), pub, FakeReporter(), settings)
    final = await run_plain(nodes, state)
    return final["result"], pub


def by_key(result):
    return {i["work_key"]: i for i in result["items"]}


async def test_unmatched_assignee_is_blocked_with_reason(settings):
    # 멤버 목록에 정하늘이 없다 -> 회의록 담당자 이름이 어떤 멤버와도 맞지 않아 10m1-a7은 담당자 미확정
    members = [m for m in MEMBERS if m.name != "정하늘"]
    result, pub = await run_case(settings, make_state(members=members))
    a7 = by_key(result)["10m1-a7"]
    assert a7["publish_status"] == "BLOCKED"
    assert a7["review_status"] == "NEEDS_CHECK" and a7["action"] == "NEEDS_CHECK"
    assert "담당자 미확정" in a7["reason"] and "정하늘" in a7["reason"]
    assert all("work_key: 10m1-a7 " not in c["body"] for c in pub.calls)  # GitHub에 쓰지 않음
    assert a7["github_issue_number"] is None
    assert "프로젝트 멤버와 이름이 맞지 않아" in a7["reason"]
    assert a7["content_json"]["assignees"]["unmatched"] == ["정하늘"]


async def test_duplicate_member_name_is_blocked(settings):
    # 박서연이 두 명이면 10m1-a1의 담당자를 정할 수 없다 -> 계정 없는 쪽이 골라져 발행되지 않아야 한다
    members = [*MEMBERS, ProjectMember(name="박서연", github_name=None)]
    result, pub = await run_case(settings, make_state(members=members))
    a1 = by_key(result)["10m1-a1"]
    assert a1["publish_status"] == "BLOCKED"
    assert "같은 이름의 멤버가 여러 명" in a1["reason"]
    assert a1["content_json"]["assignees"]["ambiguous"] == ["박서연"]
    assert not any("work_key: 10m1-a1 " in c["body"] for c in pub.calls)


async def test_member_without_github_account_is_published(settings):
    # MEMBERS의 정하늘은 멤버이지만 GitHub 계정이 없다. 10m1-a6은 정하늘 혼자 담당이라
    # 담당자는 정해졌으므로 발행하고 본문에 계정 미매핑을 남긴다 (#292)
    result, pub = await run_case(settings, make_state(members=MEMBERS))
    a6 = by_key(result)["10m1-a6"]
    assert a6["publish_status"] == "SUCCEEDED"
    assert a6["content_json"]["assignees"]["names"] == ["정하늘"]
    assert a6["content_json"]["assignees"]["unresolved"] == ["정하늘"]
    call = next(c for c in pub.calls if "work_key: 10m1-a6 " in c["body"])
    assert call["assignees"] == []  # 지정할 GitHub 계정이 없다
    assert "GitHub 계정 미매핑: 정하늘" in call["body"]
    assert any("GitHub 계정이 없어 담당자로 지정하지 못함: 정하늘" in w for w in a6["content_json"]["warnings"])


async def test_multiple_assignees_are_blocked(settings):
    # 10m1-a7은 담당자가 6명이다. 전원 멤버와 계정이 맞아도 티켓 담당자는 한 명이라 자동 발행하지 않는다 (#292)
    result, pub = await run_case(settings, make_state())
    a7 = by_key(result)["10m1-a7"]
    assert a7["content_json"]["assignees"]["unresolved"] == []  # 매핑 문제는 없다
    assert a7["publish_status"] == "BLOCKED"
    assert a7["review_status"] == "NEEDS_CHECK" and a7["action"] == "NEEDS_CHECK"
    assert "담당자 미확정" in a7["reason"] and "담당자가 여러 명" in a7["reason"]
    assert all("work_key: 10m1-a7 " not in c["body"] for c in pub.calls)  # GitHub에 쓰지 않음
    assert a7["github_issue_number"] is None


async def test_confirmed_tickets_still_publish(settings):
    result, pub = await run_case(settings, make_state(members=MEMBERS))
    published = [i for i in result["items"] if i["publish_status"] == "SUCCEEDED"]
    assert published and len(pub.calls) == len(published)
    # 발행된 항목의 미매핑 담당자는 멤버이지만 계정이 없는 정하늘뿐이다 (#292)
    assert all(set(i["content_json"]["assignees"]["unresolved"]) <= {"정하늘"} for i in published)


async def test_needs_check_stays_blocked(settings):
    result, _ = await run_case(settings, make_state())
    for key in ("10m1-a2", "10m1-a8"):  # 조건부 기존 이슈 갱신은 확인 필요
        assert by_key(result)[key]["publish_status"] == "BLOCKED"


def test_unconfirmed_reason_rules():
    ok = {"unmatched_assignees": [], "unresolved_assignees": [], "assignee_names": ["a"], "assignees": ["a"]}
    assert AgentNodes.unconfirmed_reason(ok) is None
    assert "담당자 미확정" in AgentNodes.unconfirmed_reason({**ok, "unmatched_assignees": ["홍길동"]})  # 멤버 불일치
    assert "담당자 미확정" in AgentNodes.unconfirmed_reason({**ok, "assignee_names": [], "assignees": []})  # 담당자 없음
    assert "여러 명" in AgentNodes.unconfirmed_reason({**ok, "ambiguous_assignees": ["박서연"]})  # 동명이인
    many = {**ok, "assignee_names": ["a", "b"], "assignees": ["a", "b"]}  # 둘 다 계정까지 매핑돼도
    reason = AgentNodes.unconfirmed_reason(many)
    assert "담당자가 여러 명" in reason and "a, b" in reason


def test_unconfirmed_reason_allows_member_without_account():
    # 이름이 멤버와 맞고 GitHub 계정만 없다 (#292)
    t = {"unmatched_assignees": [], "unresolved_assignees": ["정하늘"], "assignee_names": ["정하늘"], "assignees": []}
    assert AgentNodes.unconfirmed_reason(t) is None


def test_unconfirmed_reason_is_conservative_without_unmatched_field():
    # unmatched_assignees가 없는 이전 티켓은 계정 없음과 구분할 수 없어 unresolved로 막는다
    t = {"unresolved_assignees": ["홍길동"], "assignee_names": ["홍길동"], "assignees": []}
    assert "담당자 미확정" in AgentNodes.unconfirmed_reason(t)


def _ticket(**kw):
    t = {"review_status": "APPROVED", "action": "CREATE", "reason": None, "warnings": [], "unresolved_assignees": [],
         "assignees": ["a"], "title": "t", "labels": [], "dedupe": None, "publish_status": "NOT_STARTED",
         "github_issue_number": None, "github_issue_url": None, "error_message": None, "action_text": "t"}
    t.update(kw)
    return t


async def _publish(settings, tickets):
    pub = FakePublisher()
    nodes = AgentNodes(FakeLLM(), pub, FakeReporter(), settings)
    state = {"task_id": "x", "meeting_id": "m", "owner": "o", "repo": "r", "tickets": tickets}
    out = await nodes.publish(state)
    return out["tickets"], pub


async def test_no_assignee_ticket_is_blocked(settings):
    tickets, pub = await _publish(settings, [_ticket(assignees=[])])
    assert tickets[0]["publish_status"] == "BLOCKED" and not pub.calls


async def test_blocked_keeps_existing_reason(settings):
    tickets, _ = await _publish(settings, [_ticket(unresolved_assignees=["홍길동"], assignees=[], reason="보완 한도 초과")])
    assert tickets[0]["reason"].startswith("보완 한도 초과; 담당자 미확정")


async def test_link_only_ticket_is_not_blocked(settings):
    same = {"relation": "SAME", "issue_state": "open", "issue_number": 12}
    tickets, pub = await _publish(settings, [_ticket(unresolved_assignees=["홍길동"], assignees=[], dedupe=same)])
    t = tickets[0]
    assert t["publish_status"] == "SUCCEEDED" and t["github_issue_number"] == 12 and t["review_status"] == "APPROVED"
    assert not pub.calls  # 쓰기 없이 연결만


def test_auto_publish_requires_approval_in_any_environment(monkeypatch):
    from config import load_settings

    monkeypatch.setenv("PUBLISH_ON_RUN", "true")
    monkeypatch.setenv("DRY_RUN", "false")
    monkeypatch.delenv("ALLOW_AUTO_PUBLISH", raising=False)
    with pytest.raises(SettingsError):
        load_settings()
    monkeypatch.setenv("ALLOW_AUTO_PUBLISH", "true")
    assert load_settings().publish_on_run is True


def test_dry_run_does_not_need_approval():
    validate_settings(Settings(publish_on_run=True, dry_run=True))
    validate_settings(Settings(publish_on_run=False))
    with pytest.raises(SettingsError):
        validate_settings(Settings(publish_on_run=True))
