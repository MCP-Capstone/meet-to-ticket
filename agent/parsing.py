"""회의 요약 마크다운 해석 (LLM 없이 코드로).

예상 형식
    # 회의 요약 — 제목
    - 회의 ID: 10min_1
    - 회의 일시: 2026-10-19 10:00~10:10
    ## 배경 및 논의 요약
    ## 핵심 결정
    ## 액션 아이템   (| ID | 액션 | 담당자 | 기한 | 예상 티켓 처리 |)
    ## 미결 질문
    ## 이번 범위에서 제외하거나 보류한 항목
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date


@dataclass
class ActionRow:
    work_key: str
    action: str
    assignees_raw: str
    due_raw: str
    expected_raw: str


@dataclass
class ParsedSummary:
    title: str = ""
    meeting_id: str = ""
    meeting_date: date | None = None
    background: str = ""
    rows: list[ActionRow] = field(default_factory=list)
    decisions: list[str] = field(default_factory=list)
    open_questions: list[str] = field(default_factory=list)
    excluded: list[str] = field(default_factory=list)
    # 회의록에 있던 목록 섹션 (decisions, open_questions, excluded). "- 없음"뿐이어도 섹션이 있으면 들어간다
    found: set[str] = field(default_factory=set)


# 목록 섹션 이름: 제목에 들어가는 단어
_LIST_SECTIONS = {"decisions": ("결정",), "open_questions": ("미결",), "excluded": ("제외", "보류")}

_HEADER_ALIASES = {
    "id": "work_key",
    "액션": "action",
    "할 일": "action",
    "담당자": "assignees_raw",
    "담당": "assignees_raw",
    "기한": "due_raw",
    "예상 티켓 처리": "expected_raw",
    "티켓 처리": "expected_raw",
}


def _cells(line: str) -> list[str]:
    line = line.strip()
    if line.startswith("|"):
        line = line[1:]
    if line.endswith("|"):
        line = line[:-1]
    return [c.strip() for c in line.split("|")]


def _is_separator(line: str) -> bool:
    return bool(re.fullmatch(r"\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?", line.strip()))


def _split_sections(text: str) -> dict[str, list[str]]:
    sections: dict[str, list[str]] = {}
    current = "_head"
    sections[current] = []
    for line in text.splitlines():
        m = re.match(r"^##\s+(.+?)\s*$", line)
        if m:
            current = m.group(1)
            sections.setdefault(current, [])
            continue
        sections[current].append(line)
    return sections


def _find(sections: dict[str, list[str]], *keywords: str) -> list[str] | None:
    for name, lines in sections.items():
        if any(k in name for k in keywords):
            return lines
    return None


def _bullets(lines: list[str]) -> list[str]:
    out = []
    for line in lines:
        m = re.match(r"^\s*[-*]\s+(.+)$", line)
        if m:
            item = m.group(1).strip()
            if item and item not in {"별도 명시 없음", "없음"}:
                out.append(item)
    return out


def _decision_items(lines: list[str]) -> list[str]:
    """결정 섹션의 목록과 일반 문단을 원문 그대로 보존한다. 다른 섹션의 해석은 바꾸지 않는다."""
    out: list[str] = []
    paragraph: list[str] = []

    def append(item: str) -> None:
        item = item.strip()
        if item and item not in {"별도 명시 없음", "없음"}:
            out.append(item)

    def flush() -> None:
        append('\n'.join(paragraph))
        paragraph.clear()

    for line in lines:
        if not line.strip():
            flush()
            continue
        bullet = re.match(r"^\s*(?:[-*+]|\d+[.)])\s+(.+)$", line)
        if bullet:
            flush()
            append(bullet.group(1))
        else:
            paragraph.append(line)
    flush()
    return out


def _parse_table(lines: list[str]) -> list[ActionRow]:
    table = [ln for ln in lines if ln.strip().startswith("|")]
    if len(table) < 2:
        return []
    header = _cells(table[0])
    keys = [_HEADER_ALIASES.get(h.strip().lower(), _HEADER_ALIASES.get(h.strip(), "")) for h in header]
    rows: list[ActionRow] = []
    for line in table[1:]:
        if _is_separator(line):
            continue
        cells = _cells(line)
        data = {"work_key": "", "action": "", "assignees_raw": "", "due_raw": "", "expected_raw": ""}
        for key, value in zip(keys, cells):
            if key:
                data[key] = value
        if not data["action"]:
            continue
        rows.append(ActionRow(**data))
    return rows


def parse_summary(text: str) -> ParsedSummary:
    sections = _split_sections(text)
    head = sections.get("_head", [])
    result = ParsedSummary()

    for line in head:
        m = re.match(r"^#\s+(.+)$", line)
        if m and not result.title:
            result.title = m.group(1).strip()
        m = re.search(r"회의\s*ID\s*[:：]\s*(\S+)", line)
        if m:
            result.meeting_id = m.group(1).strip()
        m = re.search(r"회의\s*(?:일시|날짜)\s*[:：]\s*(\d{4})-(\d{1,2})-(\d{1,2})", line)
        if m:
            result.meeting_date = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))

    background_lines = _find(sections, "배경", "논의 요약") or []
    result.background = "\n".join(ln for ln in background_lines).strip()
    result.rows = _parse_table(_find(sections, "액션 아이템", "액션", "할 일") or [])
    for name, keywords in _LIST_SECTIONS.items():
        lines = _find(sections, *keywords)
        if lines is not None:
            result.found.add(name)
            setattr(result, name, _decision_items(lines) if name == 'decisions' else _bullets(lines))
    return result
