"""LLM 호출 (OpenAI, LangChain 구조화 출력).

nodes.py는 이 클래스와 같은 메서드를 가진 객체면 무엇이든 받는다.
테스트에서는 가짜 LLM을 넣어 OpenAI를 호출하지 않는다.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

import prompts
from config import Settings
from schemas import ActionRowOut, ActionRowsOut, DraftBatch, DraftTicket, ReviewBatch, TicketReview
from state import LLM_ERROR, AgentFatal

log = logging.getLogger("agent.llm")


def _is_auth_error(exc: Exception) -> bool:
    status = getattr(exc, "status_code", None) or getattr(getattr(exc, "response", None), "status_code", None)
    if status in (401, 403):
        return True
    text = str(exc)
    return "invalid_api_key" in text or "Error code: 401" in text


def _unsupported_param(exc: Exception) -> str | None:
    """모델이 거부한 설정 이름 (예: "Unsupported value: 'temperature' does not support 0 with this model")."""
    text = str(exc).lower()
    if "unsupported" not in text and "not support" not in text:
        return None
    if "reasoning_effort" in text or "reasoning.effort" in text:  # Chat Completions / Responses API 표기
        return "reasoning_effort"
    if "temperature" in text:
        return "temperature"
    return None


def _chat_model(**kwargs: Any) -> Any:
    from langchain_openai import ChatOpenAI  # 테스트 환경에서 import하지 않도록 지연 import

    return ChatOpenAI(**kwargs)


# 추출·초안은 writer, 검수는 reviewer 역할의 모델을 쓴다
ROLE_OF = {"rows": "writer", "draft": "writer", "review": "reviewer"}


class OpenAILLM:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._roles = {
            "writer": {"model": settings.openai_model, "reasoning_effort": settings.reasoning_effort},
            "reviewer": {"model": settings.review_model, "reasoning_effort": settings.review_reasoning_effort},
        }
        self._dropped: dict[str, set[str]] = {"writer": set(), "reviewer": set()}  # 모델이 거부해 뺀 설정
        # 실행 지표 (결과의 metrics로 나간다)
        self.stats: dict[str, Any] = {"llmCalls": 0, "llmRetries": 0, "droppedSettings": []}
        self._build()
        log.info("LLM 준비: 초안 %s, 검수 %s, 프롬프트 버전 %s",
                 settings.openai_model, settings.review_model, prompts.PROMPT_VERSION)

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
        writer = self._chat("writer")
        reviewer = self._chat("reviewer")
        self._runnables = {
            "rows": writer.with_structured_output(ActionRowsOut),
            "draft": writer.with_structured_output(DraftBatch),
            "review": reviewer.with_structured_output(ReviewBatch),
        }

    async def _invoke(self, kind: str, messages: list[tuple[str, str]]) -> Any:
        role = ROLE_OF[kind]
        while True:
            self.stats["llmCalls"] += 1
            try:
                return await self._runnables[kind].ainvoke(messages)
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
        last: Exception | None = None
        for attempt in range(self.settings.llm_retries + 1):
            try:
                return await self._invoke(kind, messages)
            except Exception as exc:  # noqa: BLE001 - 어떤 LLM 오류든 재시도 후 실패 처리
                last = exc
                log.warning("LLM %s 실패 (%d회차): %s", what, attempt + 1, exc)
                if _is_auth_error(exc):
                    raise AgentFatal(LLM_ERROR, f"OpenAI 인증 실패 (API 키 확인 필요): {exc}") from exc
                if attempt < self.settings.llm_retries:
                    self.stats["llmRetries"] += 1
                    await asyncio.sleep(1.5 * (attempt + 1))
        raise AgentFatal(LLM_ERROR, f"LLM {what} 호출 실패: {last}")

    async def extract_rows(self, summary: str) -> list[ActionRowOut]:
        out = await self._call(
            "rows", [("system", prompts.ROWS_SYSTEM), ("user", summary)], "액션 아이템 추출"
        )
        return out.rows

    async def draft(self, summary: str, items: list[dict[str, Any]]) -> list[DraftTicket]:
        user = prompts.DRAFT_USER.format(summary=summary, items=json.dumps(items, ensure_ascii=False, indent=2))
        out = await self._call("draft", [("system", prompts.DRAFT_SYSTEM), ("user", user)], "초안 작성")
        return out.tickets

    async def review(self, summary: str, drafts: list[dict[str, Any]], context: dict[str, Any] | None = None) -> list[TicketReview]:
        context = context or {}
        user = prompts.REVIEW_USER.format(
            summary=summary,
            source_note=context.get("source_note", ""),
            excluded="\n".join(f"- {x}" for x in context.get("excluded_or_held", [])) or "- 없음",
            drafts=json.dumps(drafts, ensure_ascii=False, indent=2),
        )
        out = await self._call("review", [("system", prompts.REVIEW_SYSTEM), ("user", user)], "검수")
        return out.reviews
