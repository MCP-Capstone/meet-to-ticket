"""LLM 호출 (OpenAI, LangChain 구조화 출력).

nodes.py는 이 클래스와 같은 메서드를 가진 객체면 무엇이든 받는다.
테스트에서는 가짜 LLM을 넣어 OpenAI를 호출하지 않는다.
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from typing import Any

import prompts
from config import Settings
from schemas import ActionRowsOut, DraftBatch, DraftTicket, ReviewBatch, TicketReview
from state import LLM_ERROR, AgentFatal

log = logging.getLogger("agent.llm")


def _bullet_list(items: list[str]) -> str:
    return "\n".join(f"- {x}" for x in items) or "- 없음"


def _status_code(exc: Exception) -> int | None:
    status = getattr(exc, "status_code", None) or getattr(getattr(exc, "response", None), "status_code", None)
    if isinstance(status, int):
        return status
    found = re.search(r"Error code: (\d{3})", str(exc))
    return int(found.group(1)) if found else None


def _error_kind(exc: Exception) -> str:
    """LLM 오류를 처리 방식에 따라 나눈다.

    auth: 401·403, 잘못된 키 → 즉시 중단 (다시 보내도 같다)
    request: 400·404·422 → 요청 자체가 잘못돼 재시도하지 않는다
    transient: 그 밖의 오류 (429·5xx·타임아웃·연결 오류·응답 형식 오류) → 재시도, 계속되면 대체 모델
    """
    status = _status_code(exc)
    if status in (401, 403) or "invalid_api_key" in str(exc):
        return "auth"
    if status in (400, 404, 422):
        return "request"
    return "transient"


def _unsupported_param(exc: Exception) -> str | None:
    """모델이 거부한 설정 이름 (예: "Unsupported value: 'temperature' does not support 0 with this model")."""
    text = str(exc).lower()
    # 모델에 따라 "Unsupported value"나 "Unrecognized request argument"처럼 다르게 답한다
    if not any(w in text for w in ("unsupported", "not support", "unrecognized request argument", "unknown parameter")):
        return None
    if "reasoning_effort" in text or "reasoning.effort" in text:  # Chat Completions / Responses API 표기
        return "reasoning_effort"
    if "temperature" in text:
        return "temperature"
    return None


def _chat_model(**kwargs: Any) -> Any:
    from langchain_openai import ChatOpenAI  # 테스트 환경에서 import하지 않도록 지연 import

    return ChatOpenAI(**kwargs)


# 추출·초안은 writer, 검수는 reviewer 역할의 모델을 쓴다. fallback은 재시도 후에도 실패할 때 쓰는 대체 모델
ROLE_OF = {"extract": "writer", "draft": "writer", "review": "reviewer"}
SCHEMA_OF = {"extract": ActionRowsOut, "draft": DraftBatch, "review": ReviewBatch}


class OpenAILLM:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._roles = {
            "writer": {"model": settings.openai_model, "reasoning_effort": settings.reasoning_effort},
            "reviewer": {"model": settings.review_model, "reasoning_effort": settings.review_reasoning_effort},
            # 중복 판단: 모델이 조회 도구를 골라 부른다 (구조화 출력 대신 도구 바인딩)
            "deduper": {"model": settings.dedupe_model, "reasoning_effort": settings.review_reasoning_effort},
        }
        if settings.fallback_model:
            self._roles["fallback"] = {"model": settings.fallback_model, "reasoning_effort": ""}
        self._dropped: dict[str, set[str]] = {role: set() for role in self._roles}  # 모델이 거부해 뺀 설정
        # 실행 지표 (결과의 metrics로 나간다)
        self.stats: dict[str, Any] = {
            "llmCalls": 0,
            "llmRetries": 0,
            "fallbackUsed": 0,
            "errors": {"transient": 0, "request": 0, "auth": 0},
            "droppedSettings": [],
        }
        self._build()
        log.info("LLM 준비: 초안 %s, 검수 %s, 대체 %s, 프롬프트 버전 %s", settings.openai_model,
                 settings.review_model, settings.fallback_model or "없음", prompts.PROMPT_VERSION)

    def _chat(self, role: str) -> Any:
        conf = self._roles[role]
        # 호출 1회 시간 한도. 재시도는 _call 한 곳에서만 하도록 OpenAI 클라이언트 자체 재시도는 끈다
        kwargs: dict[str, Any] = {"model": conf["model"], "timeout": self.settings.llm_timeout_seconds, "max_retries": 0}
        # 같은 회의록이면 같은 결과가 나오도록 0을 요청한다. gpt-5 계열은 추론을 끈(reasoning_effort=none)
        # 경우에만 0을 받고, 추론을 켜면 langchain-openai가 temperature를 빼서 기본값(1)으로 호출된다
        if "temperature" not in self._dropped[role]:
            kwargs["temperature"] = 0
        if conf["reasoning_effort"] and "reasoning_effort" not in self._dropped[role]:
            kwargs["reasoning_effort"] = conf["reasoning_effort"]
        return _chat_model(**kwargs)

    def _build(self) -> None:
        self._chats: dict[str, Any] = {}
        self._runnables: dict[str, dict[str, Any]] = {}
        for role in self._roles:
            chat = self._chats[role] = self._chat(role)
            kinds = [k for k, r in ROLE_OF.items() if r == role] if role != "fallback" else list(SCHEMA_OF)
            self._runnables[role] = {k: chat.with_structured_output(SCHEMA_OF[k]) for k in kinds}

    def tool_chat(self) -> Any:
        """중복 판단용 채팅 모델 (도구를 묶기 전). dedupe.py가 bind_tools로 판단 도구를 묶어 쓴다."""
        return self._chats["deduper"]

    async def _invoke(self, kind: str, messages: list[tuple[str, str]], role: str) -> Any:
        while True:
            self.stats["llmCalls"] += 1
            try:
                return await self._runnables[role][kind].ainvoke(messages)
            except Exception as exc:  # noqa: BLE001
                param = _unsupported_param(exc)
                if param is None or param in self._dropped[role]:
                    raise
                # 모델이 받지 않는 설정은 빼고 바로 다시 보낸다 (재시도 횟수에 넣지 않음)
                model = self._roles[role]["model"]
                log.warning("%s 모델이 %s 설정을 지원하지 않아 빼고 다시 호출", model, param)
                self._dropped[role].add(param)
                self.stats["droppedSettings"].append(f"{model}:{param}")
                self._build()

    async def _call(self, kind: str, messages: list[tuple[str, str]], what: str) -> Any:
        role = ROLE_OF[kind]
        last: Exception | None = None
        for attempt in range(self.settings.llm_retries + 1):
            try:
                return await self._invoke(kind, messages, role)
            except Exception as exc:  # noqa: BLE001 - 종류를 나눠 처리한다
                last = exc
                kind_of_error = _error_kind(exc)
                self.stats["errors"][kind_of_error] += 1
                log.warning("LLM %s 실패 (%d회차, %s): %s", what, attempt + 1, kind_of_error, exc)
                if kind_of_error == "auth":
                    raise AgentFatal(LLM_ERROR, f"OpenAI 인증 실패 (API 키 확인 필요): {exc}") from exc
                if kind_of_error == "request":
                    raise AgentFatal(LLM_ERROR, f"LLM {what} 요청 오류 (재시도하지 않음): {exc}") from exc
                if attempt < self.settings.llm_retries:
                    self.stats["llmRetries"] += 1
                    await asyncio.sleep(1.5 * (attempt + 1))
        # 일시적 오류가 재시도 후에도 계속되면 대체 모델로 한 번 더 시도한다
        fallback = self._roles.get("fallback")
        if fallback and fallback["model"] != self._roles[role]["model"]:
            log.warning("LLM %s 재시도 후에도 실패해 대체 모델 %s로 호출", what, fallback["model"])
            self.stats["fallbackUsed"] += 1
            try:
                return await self._invoke(kind, messages, "fallback")
            except Exception as exc:  # noqa: BLE001
                self.stats["errors"][_error_kind(exc)] += 1
                raise AgentFatal(LLM_ERROR, f"LLM {what} 호출 실패 (대체 모델 {fallback['model']} 포함): {exc}") from exc
        raise AgentFatal(LLM_ERROR, f"LLM {what} 호출 실패: {last}")

    async def extract(self, summary: str) -> ActionRowsOut:
        """표가 없는 회의록에서 할 일·결정·미결 질문·제외 항목을 뽑는다."""
        return await self._call(
            "extract", [("system", prompts.EXTRACT_SYSTEM), ("user", summary)], "회의록 추출"
        )

    async def draft(self, summary: str, items: list[dict[str, Any]], labels: list[str] | None = None,
                    context: dict[str, Any] | None = None) -> list[DraftTicket]:
        """context: 추출 결과의 회의 단위 값 (decisions: 결정 문장, open_questions: 미결 질문)."""
        context = context or {}
        user = prompts.DRAFT_USER.format(
            summary=summary,
            decisions=_bullet_list(context.get("decisions", [])),
            open_questions=_bullet_list(context.get("open_questions", [])),
            labels=_bullet_list(labels or []),
            items=json.dumps(items, ensure_ascii=False, indent=2),
        )
        out = await self._call("draft", [("system", prompts.DRAFT_SYSTEM), ("user", user)], "초안 작성")
        return out.tickets

    async def review(self, summary: str, drafts: list[dict[str, Any]], context: dict[str, Any] | None = None) -> list[TicketReview]:
        context = context or {}
        user = prompts.REVIEW_USER.format(
            summary=summary,
            source_note=context.get("source_note", ""),
            excluded=_bullet_list(context.get("excluded_or_held", [])),
            drafts=json.dumps(drafts, ensure_ascii=False, indent=2),
        )
        out = await self._call("review", [("system", prompts.REVIEW_SYSTEM), ("user", user)], "검수")
        return out.reviews
