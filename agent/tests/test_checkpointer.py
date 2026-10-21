"""checkpointer: 노드별 상태 저장, 끝난 작업 중복 실행 방지, 실행 기록 조회."""

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from publisher import DryRunPublisher, ToolCallError
from runner import history, run

pytestmark = pytest.mark.anyio


async def run_once(settings, llm=None, publisher=None):
    return await run(make_request(), settings, llm=llm or FakeLLM(), publisher=publisher or FakePublisher(),
                     reporter=FakeReporter())


async def test_published_task_is_not_run_again(settings):
    pub = FakePublisher()
    first = await run_once(settings, publisher=pub)
    assert first.summary["published"] == 6 and len(pub.calls) == 6
    second = await run_once(settings, publisher=pub)
    assert len(pub.calls) == 6  # 이슈를 다시 만들지 않는다
    assert second.model_dump() == first.model_dump()  # 같은 결과 (traceId 포함)


async def test_unknown_publish_is_not_run_again(settings):
    # 응답을 못 받아 이슈가 만들어졌을 수도 있는 작업도 다시 실행하지 않는다
    pub = FakePublisher(fail={"QA 사용자": ToolCallError("PUBLISH_FAILED", "timeout", ambiguous=True)})
    await run_once(settings, publisher=pub)
    calls = len(pub.calls)
    await run_once(settings, publisher=pub)
    assert len(pub.calls) == calls


async def test_failed_before_publish_runs_again(settings):
    pub = FakePublisher()
    first = await run_once(settings, llm=FakeLLM(fail_on="review"), publisher=pub)
    assert first.error_code == "LLM_ERROR" and pub.calls == []
    second = await run_once(settings, publisher=pub)  # 같은 taskId로 다시 보내면 처음부터 다시 실행
    assert second.status == "SUCCEEDED" and len(pub.calls) == 6
    assert second.trace_id != first.trace_id


async def test_dry_run_runs_again(settings):
    pub = DryRunPublisher()
    await run_once(settings, publisher=pub)
    await run_once(settings, publisher=pub)
    assert len(pub.calls) == 12  # 발행하지 않았으므로 다시 실행해도 된다


async def test_history_follows_graph(settings):
    result = await run_once(settings)
    steps = await history(result.task_id)
    assert [s["node"] for s in steps] == ["start", "prepare", "draft", "review", "dedupe", "publish", "finish"]
    assert [s["stage"] for s in steps[1:]] == ["NORMALIZING", "NORMALIZING", "REVIEWING", "REVIEWING", "PUBLISHING", "DONE"]
    review = steps[3]
    assert review["tickets"] == 8 and review["reviewStatus"] == {"APPROVED": 6, "NEEDS_CHECK": 2}
    assert steps[-1]["next"] == [] and steps[-1]["published"] == 6


async def test_history_shows_revision_loop(settings):
    llm = FakeLLM(review_plan={"10m1-a1": [[(4, "원문에 없는 표현")]]})
    result = await run_once(settings, llm=llm)
    nodes = [s["node"] for s in await history(result.task_id)]
    assert nodes == ["start", "prepare", "draft", "review", "draft", "review", "dedupe", "publish", "finish"]


async def test_history_unknown_task():
    assert await history("no-such-task") is None
