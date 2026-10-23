"""PUBLISH_ON_RUN=true여도 담당자 미확정/확인 필요 티켓은 외부에 쓰지 않는다 (#267).

실제 OpenAI/GitHub는 호출하지 않는다.
"""

import pytest

from conftest import MEMBERS, FakeLLM, FakePublisher, FakeReporter, make_state, run_plain
from config import Settings, SettingsError, validate_settings
from nodes import AgentNodes

pytestmark = pytest.mark.anyio


async def run_case(settings, state):
    pub = FakePublisher()
    nodes = AgentNodes(FakeLLM(), pub, FakeReporter(), settings)
    final = await run_plain(nodes, state)
    return final["result"], pub


def by_key(result):
    return {i["work_key"]: i for i in result["items"]}


async def test_unresolved_assignee_is_blocked_with_reason(settings):
    # MEMBERS의 정하늘은 GitHub 계정이 없다 -> 10m1-a7은 담당자 미확정
    result, pub = await run_case(settings, make_state(members=MEMBERS))
    a7 = by_key(result)["10m1-a7"]
    assert a7["publish_status"] == "BLOCKED"
    assert a7["review_status"] == "NEEDS_CHECK" and a7["action"] == "NEEDS_CHECK"
    assert "담당자 미확정" in a7["reason"] and "정하늘" in a7["reason"]
    assert all("work_key: 10m1-a7 " not in c["body"] for c in pub.calls)  # GitHub에 쓰지 않음
    assert a7["github_issue_number"] is None


async def test_confirmed_tickets_still_publish(settings):
    result, pub = await run_case(settings, make_state(members=MEMBERS))
    published = [i for i in result["items"] if i["publish_status"] == "SUCCEEDED"]
    assert published and len(pub.calls) == len(published)
    assert all(not i["content_json"]["assignees"]["unresolved"] for i in published)


async def test_needs_check_stays_blocked(settings):
    result, _ = await run_case(settings, make_state())
    for key in ("10m1-a2", "10m1-a8"):  # 조건부 기존 이슈 갱신은 확인 필요
        assert by_key(result)[key]["publish_status"] == "BLOCKED"


def test_unconfirmed_reason_rules():
    ok = {"unresolved_assignees": [], "assignees": ["a"]}
    assert AgentNodes.unconfirmed_reason(ok) is None
    assert "담당자 미확정" in AgentNodes.unconfirmed_reason({**ok, "unresolved_assignees": ["홍길동"]})
    assert "담당자 미확정" in AgentNodes.unconfirmed_reason({**ok, "assignees": []})  # 담당자 없음


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
