"""MCP 도구 호출과 이슈 본문 조립."""

from __future__ import annotations

import json
import re
from typing import Any

from resolve import Due
from state import MCP_UNAVAILABLE, PUBLISH_FAILED, TARGET_NOTION

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
    """외부 발행 MCP 도구를 감싼다. 도구 오류는 공통 ToolCallError로 바꾼다."""

    def __init__(self, create_issue_tool: Any, add_comment_tool: Any | None = None, update_issue_tool: Any | None = None,
                 post_message_tool: Any | None = None, create_page_tool: Any | None = None):
        self.create_tool = create_issue_tool
        self.comment_tool = add_comment_tool
        self.update_tool = update_issue_tool
        self.message_tool = post_message_tool
        self.page_tool = create_page_tool

    async def _call(self, tool: Any | None, args: dict[str, Any], name: str) -> dict[str, Any]:
        if tool is None:
            raise ToolCallError(MCP_UNAVAILABLE, f"{name} MCP 도구를 찾지 못했습니다")
        try:
            raw = await tool.ainvoke(args)
        except Exception as exc:  # noqa: BLE001
            raise error_from_exception(exc) from exc
        return parse_tool_result(raw)

    async def create_issue(self, **args: Any) -> dict[str, Any]:
        return await self._call(self.create_tool, args, "create_issue")

    async def add_comment(self, **args: Any) -> dict[str, Any]:
        return await self._call(self.comment_tool, args, "add_comment")

    async def update_issue(self, **args: Any) -> dict[str, Any]:
        return await self._call(self.update_tool, args, "update_issue")

    async def post_message(self, **args: Any) -> dict[str, Any]:
        return await self._call(self.message_tool, args, "post_message")

    async def create_page(self, **args: Any) -> dict[str, Any]:
        return await self._call(self.page_tool, args, "create_page")


class DryRunPublisher:
    """GitHub에 발행하지 않고 호출 내용만 기록한다."""

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def create_issue(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        return {"status": "dry_run", "number": None, "url": None, "assignees": args.get("assignees", []),
                "dropped_assignees": []}

    async def add_comment(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        return {"status": "dry_run", "url": None}

    async def update_issue(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        return {"status": "dry_run", "number": args.get("number"), "url": None, "dropped_labels": []}

    async def post_message(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        return {"status": "dry_run", "url": None}

    async def create_page(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        return {"status": "dry_run", "page_id": None, "url": None}


# ---------------------------------------------------------------- 이슈 본문


def _shorten(text: str, limit: int) -> str:
    text = (text or "").strip()
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def dedupe_link(dedupe: dict[str, Any] | None) -> str | None:
    """중복 판단 결과를 새 이슈 본문의 "관련" 줄로 바꾼다. 연결할 기존 이슈가 없으면 None.

    같은 작업의 열린 이슈는 새로 발행하지 않으므로(nodes.publish) 여기에는 오지 않는다.
    """
    if not dedupe or not dedupe.get("relation") or not dedupe.get("issue_number"):
        return None
    n = dedupe["issue_number"]
    closed = dedupe.get("issue_state") == "closed"
    return {
        "SAME": f"이전 작업: #{n}",
        "CHANGE": f"변경 전 작업: #{n}" if closed else f"#{n}과 다른 변경",
        "RELATED": f"관련 이슈: #{n}",
    }.get(dedupe["relation"])


MAX_LABELS = 3


def pick_labels(chosen: list[str], candidates: list[str]) -> tuple[list[str], list[str]]:
    """후보에 있는 라벨만 후보의 표기 그대로 남기고, 후보에 없어 뺀 라벨을 함께 돌려준다.

    대소문자는 무시하고 중복은 하나로 합치며 최대 MAX_LABELS개만 남긴다.
    """
    by_lower = {c.strip().lower(): c.strip() for c in candidates if c.strip()}
    picked: list[str] = []
    dropped: list[str] = []
    for label in chosen:
        label = label.strip()
        name = by_lower.get(label.lower())
        if name is None:
            if label and label not in dropped:
                dropped.append(label)
        elif name not in picked:
            picked.append(name)
    return picked[:MAX_LABELS], dropped


def related_lines(ticket: dict[str, Any]) -> tuple[list[str], list[str]]:
    """본문의 관련 이슈 줄과 상위 이슈 줄.

    회의록 예상 처리가 "하위 티켓"이면 그 키는 상위 이슈로, 아니면 관련 키로 적는다.
    중복 판단으로 찾은 기존 이슈(#번호)는 관련 이슈로 적는다.
    """
    expected = ticket.get("expected") or {}
    keys = expected.get("keys") or []
    parent = keys if expected.get("kind") == "sub_ticket" else []
    related: list[str] = []
    if expected.get("raw"):
        related.append(f"회의록 예상 처리: {expected['raw']}")
    if keys and not parent:
        related.append(f"관련 키: {', '.join(keys)}")
    link = dedupe_link(ticket.get("dedupe"))
    if link:
        related.append(link)
    return related, parent


def build_issue_body(ticket: dict[str, Any], meeting_id: str, background: str) -> str:
    """이슈 본문 (기획_v16 REQ-009). MCP는 이 본문을 그대로 올린다. 값이 없는 칸은 담당·기한을 빼고 생략한다."""
    lines: list[str] = ["## 작업 내용", ticket.get("description") or ticket.get("action_text", ""), ""]

    tasks = ticket.get("tasks") or []
    if tasks:
        lines += ["## 상세 작업", *[f"- [ ] {x}" for x in tasks], ""]

    criteria = ticket.get("acceptance_criteria") or []
    if criteria:
        lines += ["## 완료 조건", *[f"- {c}" for c in criteria], ""]

    quotes = ticket.get("evidence_quotes") or []
    if quotes:
        lines += ["## 근거"]
        for q in quotes:
            lines += [f"> {q}", ""]

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

    due = Due(**(ticket.get("due") or {"text": ""}))
    lines += ["## 기한", due.display(), ""]

    related, parent = related_lines(ticket)
    if related:
        lines += ["## 관련 이슈", *[f"- {x}" for x in related], ""]
    if parent:
        lines += ["## 상위 이슈", *[f"- {x}" for x in parent], ""]

    questions = ticket.get("open_questions") or []
    if questions:
        lines += ["## 확인 필요", *[f"- {q}" for q in questions], ""]

    notes = ticket.get("review_notes") or []
    if ticket.get("fallback") and notes:
        lines += ["## 검수 메모", "자동 검수에서 아래 지적이 남아 회의록 원문 기준 기본 초안으로 작성했습니다.",
                  *[f"- {n}" for n in notes], ""]

    if background:
        lines += ["<details>", "<summary>회의 배경</summary>", "", _shorten(background, 700), "", "</details>", ""]

    lines += ["---", f"회의: {meeting_id} · work_key: {ticket.get('work_key') or '-'} · 회의 후속조치 Agent가 작성"]
    return "\n".join(lines).strip() + "\n"


def build_notion_page(ticket: dict[str, Any], meeting_id: str, background: str) -> dict[str, str]:
    """Notion 페이지 출력문 (기획_v16 REQ-023, TASK-021). create_page(root, title, markdown)에 그대로 넘긴다.

    GitHub 이슈 본문과 같은 순서지만 GitHub 전용 칸(라벨, 관련 이슈, 상위 이슈, @계정)은 넣지 않는다.
    """
    lines: list[str] = ["## 작업 내용", ticket.get("description") or ticket.get("action_text", ""), ""]
    tasks = ticket.get("tasks") or []
    if tasks:
        lines += ["## 상세 작업", *[f"- [ ] {x}" for x in tasks], ""]
    criteria = ticket.get("acceptance_criteria") or []
    if criteria:
        lines += ["## 완료 조건", *[f"- {c}" for c in criteria], ""]
    quotes = ticket.get("evidence_quotes") or []
    if quotes:
        lines += ["## 근거", *[f"> {q}" for q in quotes], ""]
    names = ticket.get("assignee_names") or []
    lines += ["## 담당", ", ".join(names) if names else "없음", ""]
    due = Due(**(ticket.get("due") or {"text": ""}))
    lines += ["## 기한", due.display(), ""]
    questions = ticket.get("open_questions") or []
    if questions:
        lines += ["## 확인 필요", *[f"- {q}" for q in questions], ""]
    if background:
        lines += ["## 회의 배경", _shorten(background, 700), ""]
    lines += ["---", f"회의: {meeting_id} · work_key: {ticket.get('work_key') or '-'} · 회의 후속조치 Agent가 작성"]
    return {"title": ticket.get("title") or ticket.get("action_text", ""), "markdown": "\n".join(lines).strip() + "\n"}


def build_slack_notice(meeting_title: str, meeting_date: str | None, decisions: list[str],
                       tickets: list[dict[str, Any]], linked: int, members: list[str]) -> dict[str, Any]:
    """회의 결과 공지 출력문 (기획_v16 REQ-022, TASK-021). 회의 하나당 하나다.

    검토 요청은 실제로 티켓을 받는 사람과 맞춘다. GitHub 티켓은 회의록 담당자가 프로젝트 멤버 1명과
    정확히 연결될 때만 그 사람이 받고(백엔드 AssigneeResolver와 같은 규칙), 나머지는 담당 지정 필요다.
    Notion 티켓은 업로더가 받으므로 따로 센다 (REQ-012).
    검토 요청은 "@이름"으로 쓴다. 게시할 때 Slack 연결이 확인된 담당자만 post_message의 mentions로
    넘겨 멘션이 되고, 나머지는 이름 텍스트로 남는다 (#151).
    """
    head = f"*[회의 결과] {meeting_title or '회의'}*" + (f" ({meeting_date})" if meeting_date else "")
    lines = [head, ""]
    if decisions:
        lines += ["*결정 사항*", *[f"• {d}" for d in decisions], ""]
    github = [t for t in tickets if t.get("publish_target") != TARGET_NOTION]
    notion = len(tickets) - len(github)
    lines += ["*후속 작업*", f"• 새 작업 {len(tickets) - linked}건 (GitHub 이슈 {len(github) - linked}, Notion 페이지 {notion})"]
    if linked:
        lines.append(f"• 기존 이슈 연결 {linked}건")
    member_count = {name.strip(): 0 for name in members}
    for name in members:
        member_count[name.strip()] += 1
    counts: dict[str, int] = {}
    unassigned = 0
    for t in github:
        names = {n.strip() for n in t.get("assignee_names") or []}
        name = next(iter(names)) if len(names) == 1 else None
        if name is None or member_count.get(name) != 1:
            unassigned += 1  # 여러 명, 미정, 멤버가 아님, 동명이인 → 업로더가 대표 담당을 지정한다 (REQ-011)
            continue
        counts[name] = counts.get(name, 0) + 1
    if counts or unassigned or notion:
        lines += ["", "*검토 요청*", *[f"• @{name} {n}건" for name, n in counts.items()]]
        if unassigned:
            lines.append(f"• 담당 지정 필요 {unassigned}건")
        if notion:
            lines.append(f"• Notion 페이지 {notion}건 (업로더 확인)")
    lines += ["", "각자 내 티켓에서 확인한 뒤 발행해 주세요."]
    return {"text": "\n".join(lines), "reviewers": [{"name": k, "tickets": v} for k, v in counts.items()]}
