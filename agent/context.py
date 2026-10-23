"""프로젝트 맥락 불러오기 (기획_v16 REQ-020, TASK-019).

Notion 기획 문서는 회의록과 관련 있는 문서만 읽는다 (AI-MCP 합의).
- list_pages로 기획 페이지 아래 문서 목록을 받는다 ("회의 후속 티켓" 폴더는 MCP가 뺀다)
- 제목의 단어가 회의록에 나오는 문서를 관련 문서로 보고, 많이 겹치는 순서로 읽는다 (LLM을 부르지 않는다)
  흔한 단어(STOPWORDS)는 빼고, 영문은 단어 경계로, 한국어는 조사가 붙으므로 포함 여부로 본다
- 문서당 DOC_CHARS, 전체 TOTAL_CHARS에서 멈춘다
- 읽는 대로 ProjectContext에 담는다. 시간 한도로 중간에 멈춰도 그때까지 읽은 문서는 쓴다
읽은 문서는 초안 작성의 배경으로만 쓴다. 할 일 추출과 근거 인용은 회의록 원문만 쓴다.
실패하면 맥락 없이 진행한다.
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Any

from publisher import error_from_exception, parse_tool_result

log = logging.getLogger("agent.context")

DOC_CHARS = 4_000
TOTAL_CHARS = 12_000
MIN_READ_CHARS = 200  # 남은 한도가 이보다 작으면 더 읽지 않는다

_WORD = re.compile(r"[0-9A-Za-z가-힣]{2,}")
_ASCII = re.compile(r"[0-9a-z]+")

# 거의 모든 회의록에 나오는 단어라 관련 문서를 고르는 데 쓰지 않는다
STOPWORDS = {
    "회의", "회의록", "기획", "기획서", "정리", "문서", "개발", "프로젝트", "논의", "안건", "공유", "결과",
    "계획", "진행", "검토", "작업", "내용", "관련", "자료", "초안", "최종", "요약", "메모", "노트",
    "doc", "docs", "draft", "final", "meeting", "notes", "plan", "project", "the", "and", "for",
}


@dataclass
class ProjectContext:
    issues: int | None = None       # 기존 이슈 색인 개수. 색인을 쓰지 못하면 None
    notion_docs: int | None = None  # 기획 페이지 아래 문서 수. Notion이 없거나 실패하면 None
    docs: list[dict[str, str]] = field(default_factory=list)  # 읽은 문서 {"title", "text"}


def relevant_pages(pages: list[dict[str, Any]], source: str) -> list[dict[str, Any]]:
    """제목의 단어가 회의록에 나오는 문서만, 많이 겹치는 순서로 돌려준다.

    한국어는 조사가 붙으므로("내보내기를") 단어가 회의록 안에 들어 있는지로 보고,
    영문·숫자는 단어 경계로 본다("UI"가 "build"에 걸리지 않게).
    """
    text = (source or "").lower()
    ascii_words = set(_ASCII.findall(text))
    scored = []
    for i, page in enumerate(pages):
        words = {w.lower() for w in _WORD.findall(page.get("title") or "")} - STOPWORDS
        score = sum(1 for w in words if (w in ascii_words if w.isascii() else w in text))
        if score:
            scored.append((-score, i, page))
    return [page for _, _, page in sorted(scored, key=lambda x: (x[0], x[1]))]


async def load_notion(ctx: ProjectContext, list_tool: Any, read_tool: Any, page_url: str, source: str) -> None:
    """Notion 기획 문서를 읽어 ctx.notion_docs(기획 페이지 아래 문서 수)와 ctx.docs에 담는다.

    목록을 받는 즉시, 문서를 하나 읽을 때마다 담는다. 목록을 받지 못하면 그대로 둔다(None, []).
    """
    try:
        listed = parse_tool_result(await list_tool.ainvoke({"root_page_url_or_id": page_url}))
    except Exception as exc:  # noqa: BLE001
        err = error_from_exception(exc)
        log.warning("Notion 문서 목록을 받지 못해 맥락 없이 진행: [%s] %s", err.code, err.message)
        return
    pages = listed.get("pages") or []
    ctx.notion_docs = len(pages)
    used = 0
    for page in relevant_pages(pages, source):
        budget = min(DOC_CHARS, TOTAL_CHARS - used)
        if budget < MIN_READ_CHARS:
            break
        try:
            out = parse_tool_result(await read_tool.ainvoke({"page_url_or_id": page["page_id"], "max_chars": budget}))
        except Exception as exc:  # noqa: BLE001
            err = error_from_exception(exc)
            log.warning("Notion 문서 '%s'를 읽지 못해 건너뜀: [%s] %s", page.get("title"), err.code, err.message)
            continue
        text = (out.get("markdown") or "")[:budget].strip()
        if text:
            ctx.docs.append({"title": page.get("title") or "", "text": text})
            used += len(text)
    log.info("Notion 기획 문서 %d개 중 관련 문서 %d개를 읽음 (%d자)", len(pages), len(ctx.docs), used)


def planning_text(docs: list[dict[str, str]]) -> str:
    """초안 프롬프트에 넣을 기획 문서 묶음."""
    return "\n\n".join(f"### {d['title']}\n{d['text']}" for d in docs)
