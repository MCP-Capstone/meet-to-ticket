"""MCP 도구 호출과 이슈 본문 조립."""

from __future__ import annotations

import json
import re
from typing import Any

from resolve import Due
from state import MCP_UNAVAILABLE, PUBLISH_FAILED

TAG = re.compile(r"\[([A-Z][A-Z0-9_]+)\]")


class ToolCallError(Exception):
    def __init__(self, code: str, message: str, ambiguous: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.ambiguous = ambiguous  # 요청은 갔지만 결과를 모르는 경우 (UNKNOWN)


def parse_tool_result(raw: Any) -> dict[str, Any]:
    """langchain-mcp-adapters 도구 결과를 dict로 바꾼다.

    버전에 따라 str(JSON), 콘텐츠 블록 목록, (content, artifact) 튜플 등으로 온다.
    """
    if isinstance(raw, tuple) and raw:
        raw = raw[0]
    if isinstance(raw, dict):
        if "text" in raw and isinstance(raw["text"], str):
            return parse_tool_result(raw["text"])
        return raw
    if isinstance(raw, list):
        for block in raw:
            if isinstance(block, dict) and isinstance(block.get("text"), str):
                return parse_tool_result(block["text"])
            text = getattr(block, "text", None)
            if isinstance(text, str):
                return parse_tool_result(text)
        raise ToolCallError(PUBLISH_FAILED, f"도구 결과를 해석할 수 없음: {raw!r}"[:300])
    if isinstance(raw, str):
        text = raw.strip()
        tag = TAG.search(text)
        try:
            data = json.loads(text)
        except ValueError:
            if tag or text.lower().startswith("error"):
                raise ToolCallError(tag.group(1) if tag else PUBLISH_FAILED, text) from None
            raise ToolCallError(PUBLISH_FAILED, f"도구 결과가 JSON이 아님: {text[:200]}") from None
        if isinstance(data, dict) and "result" in data and len(data) == 1 and isinstance(data["result"], dict):
            return data["result"]
        return data
    raise ToolCallError(PUBLISH_FAILED, f"알 수 없는 도구 결과 형식: {type(raw)}")


def error_from_exception(exc: Exception, default_code: str = PUBLISH_FAILED) -> ToolCallError:
    if isinstance(exc, ToolCallError):
        return exc
    text = str(exc)
    tag = TAG.search(text)
    if tag:
        return ToolCallError(tag.group(1), text)
    lowered = text.lower()
    if any(k in lowered for k in ("timeout", "timed out", "readtimeout")):
        return ToolCallError(default_code, text, ambiguous=True)
    if any(k in lowered for k in ("connect", "connection refused", "unreachable")):
        return ToolCallError(MCP_UNAVAILABLE, text)
    return ToolCallError(default_code, text)


class McpPublisher:
    """MCP 서버의 create_issue 도구를 감싼다."""

    def __init__(self, create_issue_tool: Any):
        self.tool = create_issue_tool

    async def create_issue(self, **args: Any) -> dict[str, Any]:
        try:
            raw = await self.tool.ainvoke(args)
        except Exception as exc:  # noqa: BLE001
            raise error_from_exception(exc) from exc
        return parse_tool_result(raw)


class DryRunPublisher:
    """GitHub에 발행하지 않고 호출 내용만 기록한다."""

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def create_issue(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        return {"status": "dry_run", "number": None, "url": None, "assignees": args.get("assignees", []),
                "dropped_assignees": []}


# ---------------------------------------------------------------- 이슈 본문


def _shorten(text: str, limit: int) -> str:
    text = (text or "").strip()
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def build_issue_body(ticket: dict[str, Any], meeting_id: str, background: str) -> str:
    lines: list[str] = []
    if background:
        lines += ["## 배경", _shorten(background, 700), ""]
    lines += ["## 할 일", ticket.get("description") or ticket.get("action_text", ""), ""]

    criteria = ticket.get("acceptance_criteria") or []
    if criteria:
        lines += ["## 완료 조건", *[f"- [ ] {c}" for c in criteria], ""]

    quotes = ticket.get("evidence_quotes") or []
    if quotes:
        lines += ["## 근거"]
        for q in quotes:
            lines += [f"> {q}", ""]

    due = Due(**(ticket.get("due") or {"text": ""}))
    lines += ["## 기한", due.display(), ""]

    names = ticket.get("assignee_names") or []
    logins = ticket.get("assignees") or []
    unresolved = ticket.get("unresolved_assignees") or []
    lines += ["## 담당"]
    lines += [f"- 회의록 담당자: {', '.join(names) if names else '없음'}"]
    if logins:
        lines += [f"- GitHub: {', '.join('@' + x for x in logins)}"]
    if unresolved:
        lines += [f"- GitHub 계정 미매핑: {', '.join(unresolved)}"]
    lines += [""]

    expected = ticket.get("expected") or {}
    if expected.get("raw") or expected.get("keys"):
        lines += ["## 관련"]
        if expected.get("raw"):
            lines += [f"- 회의록 예상 처리: {expected['raw']}"]
        if expected.get("keys"):
            lines += [f"- 관련 키: {', '.join(expected['keys'])}"]
        lines += [""]

    notes = ticket.get("review_notes") or []
    if ticket.get("fallback") and notes:
        lines += ["## 검수 메모", "자동 검수에서 아래 지적이 남아 회의록 원문 기준 기본 초안으로 발행했습니다.",
                  *[f"- {n}" for n in notes], ""]

    questions = ticket.get("open_questions") or []
    if questions:
        lines += ["## 확인 필요", *[f"- {q}" for q in questions], ""]

    lines += ["---", f"회의: {meeting_id} · work_key: {ticket.get('work_key') or '-'} · 회의 후속조치 Agent가 자동 발행"]
    return "\n".join(lines).strip() + "\n"
