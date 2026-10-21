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


class OpenAILLM:
    def __init__(self, settings: Settings):
        from langchain_openai import ChatOpenAI  # 테스트 환경에서 import하지 않도록 지연 import

        self.settings = settings
        writer = ChatOpenAI(model=settings.openai_model, temperature=0)
        reviewer = ChatOpenAI(model=settings.review_model, temperature=0)
        self._rows = writer.with_structured_output(ActionRowsOut)
        self._draft = writer.with_structured_output(DraftBatch)
        self._review = reviewer.with_structured_output(ReviewBatch)

    async def _call(self, runnable: Any, messages: list[tuple[str, str]], what: str) -> Any:
        last: Exception | None = None
        for attempt in range(self.settings.llm_retries + 1):
            try:
                return await runnable.ainvoke(messages)
            except Exception as exc:  # noqa: BLE001 - 어떤 LLM 오류든 재시도 후 실패 처리
                last = exc
                log.warning("LLM %s 실패 (%d회차): %s", what, attempt + 1, exc)
                if _is_auth_error(exc):
                    raise AgentFatal(LLM_ERROR, f"OpenAI 인증 실패 (API 키 확인 필요): {exc}") from exc
                await asyncio.sleep(1.5 * (attempt + 1))
        raise AgentFatal(LLM_ERROR, f"LLM {what} 호출 실패: {last}")

    async def extract_rows(self, summary: str) -> list[ActionRowOut]:
        out = await self._call(
            self._rows, [("system", prompts.ROWS_SYSTEM), ("user", summary)], "액션 아이템 추출"
        )
        return out.rows

    async def draft(self, summary: str, items: list[dict[str, Any]]) -> list[DraftTicket]:
        user = prompts.DRAFT_USER.format(summary=summary, items=json.dumps(items, ensure_ascii=False, indent=2))
        out = await self._call(self._draft, [("system", prompts.DRAFT_SYSTEM), ("user", user)], "초안 작성")
        return out.tickets

    async def review(self, summary: str, drafts: list[dict[str, Any]], context: dict[str, Any] | None = None) -> list[TicketReview]:
        context = context or {}
        user = prompts.REVIEW_USER.format(
            summary=summary,
            source_note=context.get("source_note", ""),
            excluded="\n".join(f"- {x}" for x in context.get("excluded_or_held", [])) or "- 없음",
            drafts=json.dumps(drafts, ensure_ascii=False, indent=2),
        )
        out = await self._call(self._review, [("system", prompts.REVIEW_SYSTEM), ("user", user)], "검수")
        return out.reviews
