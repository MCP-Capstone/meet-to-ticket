"""trace id, 실행 지표, 진행 단계. 실제 OpenAI·GitHub·LangSmith는 호출하지 않는다."""

import uuid

import pytest

import prompts
from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from runner import run

pytestmark = pytest.mark.anyio


async def test_result_has_trace_id_and_metrics(settings):
    rep = FakeReporter()
    result = await run(make_request(), settings, llm=FakeLLM(), publisher=FakePublisher(), reporter=rep)
    assert result.status == "SUCCEEDED"
    uuid.UUID(result.trace_id)
    m = result.metrics
    assert isinstance(m["durationMs"], int) and m["durationMs"] >= 0
    assert m["promptVersion"] == prompts.PROMPT_VERSION
    assert m["model"] == settings.openai_model and m["reviewModel"] == settings.review_model
    assert m["tracing"] is False
    # 백엔드로 보내는 결과에도 같은 값이 camelCase로 들어간다
    payload = rep.results[-1]
    assert payload["traceId"] == result.trace_id
    assert payload["metrics"]["promptVersion"] == prompts.PROMPT_VERSION


async def test_progress_stages_in_order(settings):
    rep = FakeReporter()
    await run(make_request(), settings, llm=FakeLLM(), publisher=FakePublisher(), reporter=rep)
    assert [s for _, _, s in rep.progress_calls] == ["EXTRACTING", "NORMALIZING", "REVIEWING", "PUBLISHING"]


async def test_failed_run_keeps_trace_id(settings):
    rep = FakeReporter()
    result = await run(make_request(), settings, llm=FakeLLM(fail_on="review"), publisher=FakePublisher(), reporter=rep)
    assert result.status == "FAILED" and result.error_code == "LLM_ERROR"
    assert result.trace_id and rep.results[-1]["traceId"] == result.trace_id


async def test_mcp_unavailable_has_trace_id_and_metrics(settings):
    settings.mcp_server_url = "http://127.0.0.1:9/mcp"  # 아무것도 듣지 않는 포트
    settings.dry_run = False
    rep = FakeReporter()
    result = await run(make_request(), settings, llm=FakeLLM(), reporter=rep)
    assert result.error_code == "MCP_UNAVAILABLE"
    uuid.UUID(result.trace_id)
    assert result.metrics["promptVersion"] == prompts.PROMPT_VERSION
    assert rep.results[-1]["traceId"] == result.trace_id


async def test_job_reporter_shows_stage_while_running():
    pytest.importorskip("fastapi")
    import main

    inner = FakeReporter()
    reporter = main.JobReporter(inner)
    main._jobs["t-stage"] = main._running("t-stage")
    assert main._jobs["t-stage"]["stage"] == "EXTRACTING"
    await reporter.progress("t-stage", "RUNNING", "REVIEWING")
    assert main._jobs["t-stage"] == {"taskId": "t-stage", "status": "RUNNING", "stage": "REVIEWING"}
    assert inner.progress_calls == [("t-stage", "RUNNING", "REVIEWING")]  # 백엔드 보고도 그대로
    # 끝난 작업의 결과는 바꾸지 않는다
    main._jobs["t-stage"] = {"taskId": "t-stage", "status": "SUCCEEDED", "stage": "DONE"}
    await reporter.progress("t-stage", "RUNNING", "PUBLISHING")
    assert main._jobs["t-stage"]["stage"] == "DONE"
    del main._jobs["t-stage"]
