"""프로젝트 맥락 불러오기 (기획_v16 REQ-020, TASK-019).

Notion 기획 문서는 회의록과 관련 있는 문서만 읽는다 (AI-MCP 합의).
- list_pages로 기획 페이지 아래 문서 목록을 받는다 ("회의 후속 티켓" 폴더는 MCP가 뺀다)
- 제목의 단어가 회의록에 나오는 문서를 관련 문서로 보고, 많이 겹치는 순서로 읽는다 (LLM을 부르지 않는다)
- 문서당 DOC_CHARS, 전체 TOTAL_CHARS에서 멈춘다
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


@dataclass
class ProjectContext:
    issues: int | None = None       # 기존 이슈 색인 개수. 색인을 쓰지 못하면 None
    notion_docs: int | None = None  # 기획 페이지 아래 문서 수. Notion이 없거나 실패하면 None
    docs: list[dict[str, str]] = field(default_factory=list)  # 읽은 문서 {"title", "text"}


def relevant_pages(pages: list[dict[str, Any]], source: str) -> list[dict[str, Any]]:
    """제목의 단어가 회의록에 나오는 문서만, 많이 겹치는 순서로 돌려준다.

    한국어는 조사가 붙으므로("내보내기를") 단어가 회의록 안에 들어 있는지로 본다.
    """
    text = (source or "").lower()
    scored = []
    for i, page in enumerate(pages):
        words = {w.lower() for w in _WORD.findall(page.get("title") or "")}
        score = sum(1 for w in words if w in text)
        if score:
            scored.append((-score, i, page))
    return [page for _, _, page in sorted(scored, key=lambda x: (x[0], x[1]))]


async def load_notion(list_tool: Any, read_tool: Any, page_url: str, source: str) -> tuple[int | None, list[dict[str, str]]]:
    """(기획 페이지 아래 문서 수, 읽은 문서). 목록을 받지 못하면 (None, [])."""
    try:
        listed = parse_tool_result(await list_tool.ainvoke({"root_page_url_or_id": page_url}))
    except Exception as exc:  # noqa: BLE001
        err = error_from_exception(exc)
        log.warning("Notion 문서 목록을 받지 못해 맥락 없이 진행: [%s] %s", err.code, err.message)
        return None, []
    pages = listed.get("pages") or []
    docs: list[dict[str, str]] = []
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
            docs.append({"title": page.get("title") or "", "text": text})
            used += len(text)
    log.info("Notion 기획 문서 %d개 중 관련 문서 %d개를 읽음 (%d자)", len(pages), len(docs), used)
    return len(pages), docs


def planning_text(docs: list[dict[str, str]]) -> str:
    """초안 프롬프트에 넣을 기획 문서 묶음."""
    return "\n\n".join(f"### {d['title']}\n{d['text']}" for d in docs)
