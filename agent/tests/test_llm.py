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
UNRECOGNIZED_ERROR = (
    "Error code: 400 - {'error': {'message': 'Unrecognized request argument supplied: reasoning_effort', "
    "'type': 'invalid_request_error', 'param': None, 'code': None}}"
)
EMPTY = {ActionRowsOut: ActionRowsOut(rows=[]), DraftBatch: DraftBatch(tickets=[]), ReviewBatch: ReviewBatch(reviews=[])}


class FakeChat:
    """ChatOpenAI 대신 쓰는 가짜.

    reject에 든 (모델, 설정)은 그 설정을 보내면 400을 낸다.
    error는 모든 호출, failing[모델]은 그 모델의 모든 호출, script[모델]은 그 모델의 호출 순서대로 낼 오류(None이면 성공).
    """

    built: list[dict] = []
    calls: list[str] = []
    reject: set[tuple[str, str]] = set()
    error: str | None = None
    failing: dict[str, str] = {}
    script: dict[str, list] = {}

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
        if model in FakeChat.failing:
            raise RuntimeError(FakeChat.failing[model])
        if FakeChat.script.get(model):
            planned = FakeChat.script[model].pop(0)
            if planned:
                raise RuntimeError(planned)
        if (model, "reasoning_effort") in FakeChat.reject and "reasoning_effort" in self.kwargs:
            raise RuntimeError(EFFORT_ERROR)
        if (model, "temperature") in FakeChat.reject and "temperature" in self.kwargs:
            raise RuntimeError(TEMPERATURE_ERROR)
        return EMPTY[self.schema]


@pytest.fixture(autouse=True)
def fake_chat(monkeypatch):
    FakeChat.built, FakeChat.calls, FakeChat.reject, FakeChat.error = [], [], set(), None
    FakeChat.failing, FakeChat.script = {}, {}
    monkeypatch.setattr(llm, "_chat_model", FakeChat)

    async def no_sleep(_):
        return None

    monkeypatch.setattr(llm.asyncio, "sleep", no_sleep)


# 모든 호출에 시간 한도를 두고, OpenAI 클라이언트 자체 재시도는 끈다 (재시도는 _call 한 곳에서)
LIMITS = {"timeout": 120, "max_retries": 0}
WRITER = {"model": "gpt-5.4-nano", **LIMITS, "temperature": 0, "reasoning_effort": "none"}
REVIEWER = {"model": "gpt-5.4-mini", **LIMITS, "temperature": 0}
DEDUPER = {"model": "gpt-5.4-mini", **LIMITS, "temperature": 0}  # 중복 판단 (도구 고르기)


async def test_default_roles():
    client = llm.OpenAILLM(Settings())
    # 추출·초안은 추론을 끄고 temperature 0, 검수·중복 판단은 모델 기본 추론
    assert FakeChat.built == [WRITER, REVIEWER, DEDUPER]
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
    assert FakeChat.built[-3:] == [{"model": "gpt-5.4-nano", **LIMITS, "reasoning_effort": "none"}, REVIEWER, DEDUPER]
    FakeChat.calls.clear()
    await client.draft("요약", [])
    assert FakeChat.calls == ["gpt-5.4-nano"]  # 이후 호출은 처음부터 바뀐 설정으로


async def test_model_rejecting_reasoning_none_drops_it():
    FakeChat.reject = {("gpt-5.4-nano", "reasoning_effort")}
    client = llm.OpenAILLM(Settings())
    assert await client.extract_rows("요약") == []
    assert FakeChat.calls == ["gpt-5.4-nano", "gpt-5.4-nano"]
    assert FakeChat.built[-3] == {"model": "gpt-5.4-nano", **LIMITS, "temperature": 0}


@pytest.mark.parametrize("message", [UNRECOGNIZED_ERROR, "Error code: 400 - Unknown parameter: 'reasoning_effort'."])
async def test_unrecognized_argument_wording_drops_reasoning_effort(message):
    # reasoning_effort를 모르는 모델(gpt-4o-mini 등)은 "Unsupported"가 아닌 다른 문구로 거부한다
    FakeChat.script = {"gpt-5.4-nano": [message]}
    client = llm.OpenAILLM(Settings())
    assert await client.extract_rows("요약") == []
    assert FakeChat.calls == ["gpt-5.4-nano", "gpt-5.4-nano"]  # 빼고 바로 다시 보냄, 치명 오류 아님
    assert FakeChat.built[-3] == {"model": "gpt-5.4-nano", **LIMITS, "temperature": 0}
    assert client.stats["droppedSettings"] == ["gpt-5.4-nano:reasoning_effort"]


async def test_other_request_errors_fail_without_retry_or_drop():
    FakeChat.error = "Error code: 400 - {'error': {'message': 'Invalid schema'}}"
    client = llm.OpenAILLM(Settings(llm_retries=1))
    with pytest.raises(AgentFatal, match="요청 오류"):
        await client.extract_rows("요약")
    assert FakeChat.calls == ["gpt-5.4-nano"]  # 같은 요청은 다시 보내도 같아서 재시도하지 않음
    assert FakeChat.built == [WRITER, REVIEWER, DEDUPER]  # 설정을 빼고 다시 만들지 않음


async def test_stats_count_calls_retries_and_dropped_settings():
    FakeChat.reject = {("gpt-5.4-nano", "temperature")}
    client = llm.OpenAILLM(Settings(llm_retries=1))
    await client.extract_rows("요약")  # 거부 → 설정 빼고 재호출 (재시도 아님)
    assert client.stats == {"llmCalls": 2, "llmRetries": 0, "fallbackUsed": 0,
                            "errors": {"transient": 0, "request": 0, "auth": 0},
                            "droppedSettings": ["gpt-5.4-nano:temperature"]}
    FakeChat.error = "Error code: 500 - server error"
    with pytest.raises(AgentFatal):
        await client.review("요약", [])
    assert client.stats["llmCalls"] == 4 and client.stats["llmRetries"] == 1


async def test_review_reasoning_effort_setting():
    llm.OpenAILLM(Settings(reasoning_effort="", review_reasoning_effort="high"))
    assert FakeChat.built == [{"model": "gpt-5.4-nano", **LIMITS, "temperature": 0}, {**REVIEWER, "reasoning_effort": "high"},
                              {**DEDUPER, "reasoning_effort": "high"}]


async def test_timeout_setting_is_passed():
    llm.OpenAILLM(Settings(llm_timeout_seconds=30))
    assert all(k["timeout"] == 30 and k["max_retries"] == 0 for k in FakeChat.built)


RATE_LIMIT = "Error code: 429 - {'error': {'message': 'Rate limit reached'}}"
UNAVAILABLE = "Error code: 503 - {'error': {'message': 'Service unavailable'}}"
UNAUTHORIZED = "Error code: 401 - {'error': {'code': 'invalid_api_key'}}"


async def test_transient_errors_are_retried():
    FakeChat.script = {"gpt-5.4-nano": [RATE_LIMIT, RATE_LIMIT, None]}
    client = llm.OpenAILLM(Settings())
    assert await client.extract_rows("요약") == []
    assert FakeChat.calls == ["gpt-5.4-nano"] * 3
    assert client.stats["llmRetries"] == 2 and client.stats["errors"]["transient"] == 2


async def test_timeout_and_connection_errors_are_transient():
    assert llm._error_kind(RuntimeError("Request timed out.")) == "transient"
    assert llm._error_kind(RuntimeError("Connection error.")) == "transient"
    assert llm._error_kind(RuntimeError(UNAVAILABLE)) == "transient"
    assert llm._error_kind(RuntimeError("Error code: 422 - bad")) == "request"
    assert llm._error_kind(RuntimeError(UNAUTHORIZED)) == "auth"


async def test_fallback_model_after_retries():
    FakeChat.failing = {"gpt-5.4-mini": UNAVAILABLE}
    client = llm.OpenAILLM(Settings(fallback_model="gpt-5.4"))
    assert FakeChat.built[-1] == {"model": "gpt-5.4", **LIMITS, "temperature": 0}
    assert await client.review("요약", []) == []
    # 검수 모델로 3번(재시도 2번) 실패한 뒤 대체 모델로 1번 성공
    assert FakeChat.calls == ["gpt-5.4-mini"] * 3 + ["gpt-5.4"]
    assert client.stats["fallbackUsed"] == 1 and client.stats["errors"]["transient"] == 3


async def test_without_fallback_transient_errors_fail():
    FakeChat.failing = {"gpt-5.4-mini": UNAVAILABLE}
    client = llm.OpenAILLM(Settings())
    with pytest.raises(AgentFatal, match="호출 실패"):
        await client.review("요약", [])
    assert FakeChat.calls == ["gpt-5.4-mini"] * 3 and client.stats["fallbackUsed"] == 0


async def test_fallback_failure_is_reported():
    FakeChat.failing = {"gpt-5.4-mini": UNAVAILABLE, "gpt-5.4": UNAVAILABLE}
    client = llm.OpenAILLM(Settings(fallback_model="gpt-5.4"))
    with pytest.raises(AgentFatal, match="대체 모델 gpt-5.4 포함"):
        await client.review("요약", [])
    assert client.stats["fallbackUsed"] == 1 and client.stats["errors"]["transient"] == 4


async def test_auth_and_request_errors_do_not_use_fallback():
    for error, label in ((UNAUTHORIZED, "인증 실패"), ("Error code: 400 - bad request", "요청 오류")):
        FakeChat.calls.clear()
        FakeChat.failing = {"gpt-5.4-mini": error}
        client = llm.OpenAILLM(Settings(fallback_model="gpt-5.4"))
        with pytest.raises(AgentFatal, match=label):
            await client.review("요약", [])
        assert FakeChat.calls == ["gpt-5.4-mini"] and client.stats["fallbackUsed"] == 0


async def test_tool_chat_for_dedupe():
    client = llm.OpenAILLM(Settings(dedupe_model="gpt-5.4"))
    chat = client.tool_chat()
    assert chat.kwargs == {"model": "gpt-5.4", **LIMITS, "temperature": 0}  # 도구를 묶기 전 채팅 모델
