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
    ok = {"review_status": "APPROVED", "action": "CREATE", "unresolved_assignees": [], "assignees": ["a"]}
    assert AgentNodes.unconfirmed_reason(ok) is None
    assert "담당자 미확정" in AgentNodes.unconfirmed_reason({**ok, "unresolved_assignees": ["홍길동"]})
    assert "확인 필요" in AgentNodes.unconfirmed_reason({**ok, "review_status": "NEEDS_CHECK"})
    assert "확인 필요" in AgentNodes.unconfirmed_reason({**ok, "action": "NEEDS_CHECK"})


@pytest.mark.parametrize("env", ["prod", "PRODUCTION"])
def test_production_rejects_auto_publish_without_approval(env):
    with pytest.raises(SettingsError):
        validate_settings(Settings(publish_on_run=True, app_env=env))


def test_production_allows_approved_auto_publish_and_other_cases():
    validate_settings(Settings(publish_on_run=True, app_env="prod", allow_auto_publish=True))
    validate_settings(Settings(publish_on_run=False, app_env="prod"))
    validate_settings(Settings(publish_on_run=True, app_env="dev"))
    validate_settings(Settings(publish_on_run=True))
