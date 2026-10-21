"""실행 한도: 작업 마감 시간, 회의 원문 길이. 실제 OpenAI·GitHub는 호출하지 않는다."""

import pytest

import nodes as nodes_module
from conftest import FakeLLM, FakePublisher, FakeReporter, make_request, run_plain
from nodes import AgentNodes
from runner import initial_state, run

pytestmark = pytest.mark.anyio


class Clock:
    """nodes.py가 보는 시계. 테스트가 시각을 옮긴다."""

    def __init__(self, now: float = 0.0):
        self.now = now

    def time(self) -> float:
        return self.now


@pytest.fixture
def clock(monkeypatch):
    c = Clock()
    monkeypatch.setattr(nodes_module, "time", c)
    return c


def state_with_deadline(deadline_at):
    state = initial_state(make_request())
    state["deadline_at"] = deadline_at
    return state


async def run_case(settings, state, llm=None, publisher=None):
    nodes = AgentNodes(llm or FakeLLM(), publisher or FakePublisher(), FakeReporter(), settings)
    return (await run_plain(nodes, state))["result"]


async def test_deadline_passed_before_draft(settings, clock):
    clock.now = 2000
    llm, pub = FakeLLM(), FakePublisher()
    result = await run_case(settings, state_with_deadline(1000), llm=llm, publisher=pub)
    assert result["status"] == "FAILED" and result["error_code"] == "TASK_TIMEOUT"
    assert "600초" in result["error_message"]
    assert llm.draft_calls == [] and pub.calls == []


async def test_deadline_passed_during_revision_stops_before_publish(settings, clock):
    class SlowReviewLLM(FakeLLM):
        async def review(self, summary, drafts, context=None):
            out = await super().review(summary, drafts, context)
            clock.now = 2000  # 첫 검수가 끝나는 사이에 마감이 지남
            return out

    llm = SlowReviewLLM(review_plan={"10m1-a1": [[(4, "원문에 없는 표현")]]})
    pub = FakePublisher()
    result = await run_case(settings, state_with_deadline(1000), llm=llm, publisher=pub)
    assert result["error_code"] == "TASK_TIMEOUT"
    assert len(llm.draft_calls) == 1  # 보완 초안은 쓰지 않았다
    assert pub.calls == []


async def test_publish_is_not_cut_by_deadline(settings, clock):
    class SlowPublisher(FakePublisher):
        async def create_issue(self, **args):
            clock.now = 2000  # 발행 도중 마감이 지나도
            return await super().create_issue(**args)

    pub = SlowPublisher()
    result = await run_case(settings, state_with_deadline(1000), publisher=pub)
    assert result["status"] == "SUCCEEDED"
    assert len(pub.calls) == 6 and result["summary"]["published"] == 6  # 끝까지 발행한다


async def test_no_deadline_when_disabled(settings):
    assert initial_state(make_request())["deadline_at"] is None
    state = initial_state(make_request(), deadline_seconds=600)
    assert state["deadline_at"] == pytest.approx(state["started_at"] + 600)


async def test_too_long_source_skips_llm(settings):
    settings.max_source_chars = 100
    llm, pub = FakeLLM(), FakePublisher()
    result = await run(make_request(), settings, llm=llm, publisher=pub, reporter=FakeReporter())
    assert result.status == "FAILED" and result.error_code == "INPUT_TOO_LONG"
    assert result.stage == "EXTRACTING" and "한도 100자" in result.error_message
    assert llm.draft_calls == [] and llm.review_contexts == [] and pub.calls == []


async def test_source_limit_disabled(settings):
    settings.max_source_chars = 0
    result = await run(make_request(), settings, llm=FakeLLM(), publisher=FakePublisher(), reporter=FakeReporter())
    assert result.status == "SUCCEEDED"
