"""Notion 블록과 제한된 Markdown 사이의 변환.

회의 맥락을 읽고 티켓 페이지를 만들기 위한 공통 부분만 지원한다. 지원하지 않는 블록은
plain_text를 보존해 문서 의미가 조용히 사라지지 않게 한다.
"""

from __future__ import annotations

import re
from typing import Any

MAX_RICH_TEXT = 2000  # Notion rich_text.text.content 한도
MAX_CHILDREN = 100  # Notion 한 번의 page create children 한도


def _text(items: list[dict[str, Any]] | None) -> str:
    """Notion rich_text 배열의 사람이 읽을 값을 합친다."""
    return "".join(item.get("plain_text") or item.get("text", {}).get("content", "") for item in items or [])


def blocks_to_markdown(blocks: list[dict[str, Any]]) -> str:
    """조회한 최상위 Notion 블록을 읽기용 Markdown으로 바꾼다."""
    lines: list[str] = []
    for block in blocks:
        kind = block.get("type", "")
        data = block.get(kind, {}) if isinstance(block.get(kind), dict) else {}
        text = _text(data.get("rich_text"))
        if kind.startswith("heading_"):
            lines.append("#" * int(kind[-1]) + " " + text)
        elif kind == "paragraph":
            lines.append(text)
        elif kind == "bulleted_list_item":
            lines.append(f"- {text}")
        elif kind == "numbered_list_item":
            lines.append(f"1. {text}")
        elif kind == "to_do":
            lines.append(f"- [{'x' if data.get('checked') else ' '}] {text}")
        elif kind == "quote":
            lines.append(f"> {text}")
        elif kind == "code":
            lines.append(f"```{data.get('language', '')}\n{text}\n```")
        elif kind == "divider":
            lines.append("---")
        elif kind == "child_page":
            lines.append(f"[하위 페이지] {data.get('title', '')}")
        elif text:
            lines.append(text)
    return "\n\n".join(line for line in lines if line).strip()


def _rich_text(text: str) -> list[dict[str, Any]]:
    # 긴 줄도 Notion 허용 길이마다 쪼개어 보존한다.
    return [{"type": "text", "text": {"content": text[i : i + MAX_RICH_TEXT]}} for i in range(0, len(text), MAX_RICH_TEXT)] or []


def _block(kind: str, text: str, **extra: Any) -> dict[str, Any]:
    return {"object": "block", "type": kind, kind: {"rich_text": _rich_text(text), **extra}}


_NUMBERED = re.compile(r"^\d+\.\s+(.*)$")


def markdown_to_blocks(markdown: str) -> list[dict[str, Any]]:
    """티켓 본문에서 쓰는 기본 Markdown을 Notion children 블록으로 바꾼다."""
    blocks: list[dict[str, Any]] = []
    code_lines: list[str] | None = None
    code_language = "plain text"

    def paragraph(text: str) -> None:
        if text:
            blocks.append(_block("paragraph", text))

    for raw in (markdown or "").splitlines():
        line = raw.rstrip()
        if line.startswith("```"):
            if code_lines is None:
                code_language = line[3:].strip() or "plain text"
                code_lines = []
            else:
                blocks.append(_block("code", "\n".join(code_lines), language=code_language))
                code_lines = None
            continue
        if code_lines is not None:
            code_lines.append(raw)
            continue
        if not line.strip():
            continue
        if line == "---":
            blocks.append({"object": "block", "type": "divider", "divider": {}})
        elif line.startswith("### "):
            blocks.append(_block("heading_3", line[4:]))
        elif line.startswith("## "):
            blocks.append(_block("heading_2", line[3:]))
        elif line.startswith("# "):
            blocks.append(_block("heading_1", line[2:]))
        elif line.startswith("- [x] ") or line.startswith("- [X] "):
            blocks.append(_block("to_do", line[6:], checked=True))
        elif line.startswith("- [ ] "):
            blocks.append(_block("to_do", line[6:], checked=False))
        elif line.startswith("- ") or line.startswith("* "):
            blocks.append(_block("bulleted_list_item", line[2:]))
        elif matched := _NUMBERED.match(line):
            blocks.append(_block("numbered_list_item", matched.group(1)))
        elif line.startswith("> "):
            blocks.append(_block("quote", line[2:]))
        else:
            paragraph(line)
    if code_lines is not None:
        blocks.append(_block("code", "\n".join(code_lines), language=code_language))
    return blocks


def batches(blocks: list[dict[str, Any]]) -> list[list[dict[str, Any]]]:
    """Notion children 제한에 맞춰 100개 단위로 나눈다."""
    return [blocks[i : i + MAX_CHILDREN] for i in range(0, len(blocks), MAX_CHILDREN)]
