"""OpenAILLM의 모델 설정과, 모델이 받지 않는 설정 처리. 실제 OpenAI는 호출하지 않는다."""

import pytest

import llm
from config import Settings
from schemas import ActionRowsOut, DraftBatch, ReviewBatch
from state import AgentFatal

pytestmark = pytest.mark.anyio

TEMPERATURE_ERROR = (
    "Error code: 400 - {'error': {'message': \"Unsupported value: 'temperature' does not support 0 "
    "with this model. Only the default (1) value is supported.\", 'param': 'temperature'}}"
)
EFFORT_ERROR = (
    "Error code: 400 - {'error': {'message': \"Unsupported value: 'reasoning_effort' does not support "
    "'none' with this model.\", 'param': 'reasoning_effort'}}"
)
EMPTY = {ActionRowsOut: ActionRowsOut(rows=[]), DraftBatch: DraftBatch(tickets=[]), ReviewBatch: ReviewBatch(reviews=[])}


class FakeChat:
    """ChatOpenAI 대신 쓰는 가짜. reject에 든 (모델, 설정)은 그 설정을 보내면 400을 낸다."""

    built: list[dict] = []
    calls: list[str] = []
    reject: set[tuple[str, str]] = set()
    error: str | None = None

    def __init__(self, **kwargs):
        self.kwargs = kwargs
        FakeChat.built.append(kwargs)

    def with_structured_output(self, schema):
        return FakeRunnable(self.kwargs, schema)


class FakeRunnable:
    def __init__(self, kwargs, schema):
        self.kwargs = kwargs
        self.schema = schema

    async def ainvoke(self, messages):
        model = self.kwargs["model"]
        FakeChat.calls.append(model)
        if FakeChat.error:
            raise RuntimeError(FakeChat.error)
        if (model, "reasoning_effort") in FakeChat.reject and "reasoning_effort" in self.kwargs:
            raise RuntimeError(EFFORT_ERROR)
        if (model, "temperature") in FakeChat.reject and "temperature" in self.kwargs:
            raise RuntimeError(TEMPERATURE_ERROR)
        return EMPTY[self.schema]


@pytest.fixture(autouse=True)
def fake_chat(monkeypatch):
    FakeChat.built, FakeChat.calls, FakeChat.reject, FakeChat.error = [], [], set(), None
    monkeypatch.setattr(llm, "_chat_model", FakeChat)

    async def no_sleep(_):
        return None

    monkeypatch.setattr(llm.asyncio, "sleep", no_sleep)


# 모든 호출에 시간 한도를 두고, OpenAI 클라이언트 자체 재시도는 끈다 (재시도는 _call 한 곳에서)
LIMITS = {"timeout": 120, "max_retries": 0}
WRITER = {"model": "gpt-5.4-nano", **LIMITS, "temperature": 0, "reasoning_effort": "none"}
REVIEWER = {"model": "gpt-5.4-mini", **LIMITS, "temperature": 0}


async def test_default_roles():
    client = llm.OpenAILLM(Settings())
    # 추출·초안은 추론을 끄고 temperature 0, 검수는 모델 기본 추론
    assert FakeChat.built == [WRITER, REVIEWER]
    await client.extract_rows("요약")
    await client.draft("요약", [])
    await client.review("요약", [])
    assert FakeChat.calls == ["gpt-5.4-nano", "gpt-5.4-nano", "gpt-5.4-mini"]


async def test_model_rejecting_temperature_uses_default():
    FakeChat.reject = {("gpt-5.4-nano", "temperature")}
    client = llm.OpenAILLM(Settings())
    assert await client.extract_rows("요약") == []
    # 거부한 writer만 temperature 없이 다시 만들고, 재시도 없이 바로 다시 보낸다
    assert FakeChat.calls == ["gpt-5.4-nano", "gpt-5.4-nano"]
    assert FakeChat.built[-2:] == [{"model": "gpt-5.4-nano", **LIMITS, "reasoning_effort": "none"}, REVIEWER]
    FakeChat.calls.clear()
    await client.draft("요약", [])
    assert FakeChat.calls == ["gpt-5.4-nano"]  # 이후 호출은 처음부터 바뀐 설정으로


async def test_model_rejecting_reasoning_none_drops_it():
    FakeChat.reject = {("gpt-5.4-nano", "reasoning_effort")}
    client = llm.OpenAILLM(Settings())
    assert await client.extract_rows("요약") == []
    assert FakeChat.calls == ["gpt-5.4-nano", "gpt-5.4-nano"]
    assert FakeChat.built[-2] == {"model": "gpt-5.4-nano", **LIMITS, "temperature": 0}


async def test_other_errors_are_retried_not_dropped():
    FakeChat.error = "Error code: 400 - {'error': {'message': 'Invalid schema'}}"
    client = llm.OpenAILLM(Settings(llm_retries=1))
    with pytest.raises(AgentFatal):
        await client.extract_rows("요약")
    assert FakeChat.calls == ["gpt-5.4-nano", "gpt-5.4-nano"]  # 재시도 1회
    assert FakeChat.built == [WRITER, REVIEWER]  # 설정을 빼고 다시 만들지 않음


async def test_stats_count_calls_retries_and_dropped_settings():
    FakeChat.reject = {("gpt-5.4-nano", "temperature")}
    client = llm.OpenAILLM(Settings(llm_retries=1))
    await client.extract_rows("요약")  # 거부 → 설정 빼고 재호출 (재시도 아님)
    assert client.stats == {"llmCalls": 2, "llmRetries": 0, "droppedSettings": ["gpt-5.4-nano:temperature"]}
    FakeChat.error = "Error code: 500 - server error"
    with pytest.raises(AgentFatal):
        await client.review("요약", [])
    assert client.stats["llmCalls"] == 4 and client.stats["llmRetries"] == 1


async def test_review_reasoning_effort_setting():
    llm.OpenAILLM(Settings(reasoning_effort="", review_reasoning_effort="high"))
    assert FakeChat.built == [{"model": "gpt-5.4-nano", **LIMITS, "temperature": 0}, {**REVIEWER, "reasoning_effort": "high"}]


async def test_timeout_setting_is_passed():
    llm.OpenAILLM(Settings(llm_timeout_seconds=30))
    assert all(k["timeout"] == 30 and k["max_retries"] == 0 for k in FakeChat.built)
