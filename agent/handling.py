"""중복 판단 결과로 처리 유형을 정하고, 연결 댓글과 갱신 섹션 문안을 만든다 (기획_v16 1.7.2 처리 유형 표).

처리 유형은 제안이다. 담당자가 카드에서 바꿀 수 있고(REQ-015), 실제 GitHub 쓰기는 일괄 발행(TASK-034)이
`create_issue`, `add_comment`, `update_issue`로 실행한다. 이 모듈은 GitHub를 호출하지 않는다.

| 판정 | 열린 이슈 | 닫힌 이슈 |
|---|---|---|
| SAME | 연결 (댓글) | 신규 발행 + 기존 번호 표기, 겹칠 수 있음 |
| CHANGE | 갱신 (본문 끝에 변경 섹션) | 신규 발행 + 기존 번호 표기, 겹칠 수 있음 |
| RELATED | 신규 발행 + 관련 이슈 표기, 겹칠 수 있음 | 같음 |
| UNRELATED, 판단 없음 | 신규 발행 | 신규 발행 |
"""

from __future__ import annotations

from typing import Any, Literal

CREATE, LINK, UPDATE = "CREATE", "LINK", "UPDATE"
Handling = Literal["CREATE", "LINK", "UPDATE"]


def decide(dedupe: dict[str, Any] | None) -> dict[str, Any]:
    """판정 결과(dedupe.py의 결과)로 처리 유형을 정한다.

    - type: CREATE(신규 발행) / LINK(기존 이슈에 댓글로 연결) / UPDATE(기존 이슈 갱신)
    - issue_number: 연결, 갱신 대상이거나 신규 발행 본문에 적을 기존 이슈 번호. 없으면 None
    - overlap: 카드에 "겹칠 수 있음" 경고를 띄울지
    - judged: 중복 판단을 했는지. False면 카드에 "중복 판단 안 됨"을 띄운다
    """
    d = dedupe or {}
    relation, number = d.get("relation"), d.get("issue_number")
    if not relation:
        return _handling(CREATE, None, overlap=False, judged=False)
    if relation == "UNRELATED" or not number:
        return _handling(CREATE, None, overlap=False, judged=True)
    is_open = d.get("issue_state") == "open"
    if relation == "SAME" and is_open:
        return _handling(LINK, number, overlap=False, judged=True)
    if relation == "CHANGE" and is_open:
        return _handling(UPDATE, number, overlap=False, judged=True)
    # 닫힌 이슈는 다시 열지 않는다. RELATED는 다른 작업이라 새로 만든다
    return _handling(CREATE, number, overlap=True, judged=True)


def after_recheck(confirmed: dict[str, Any] | None, rechecked: dict[str, Any] | None) -> dict[str, Any]:
    """발행 직전 재대조(TASK-042) 뒤 실제로 실행할 처리 유형.

    재대조가 열린 같은 작업(LINK)을 찾으면 연결로 바꾼다. 이미 확인된 티켓이므로 다시 묻지 않고 댓글로 연결한다.
    단 담당자가 카드에서 직접 바꾼 유형(confirmed["edited"]가 true)은 재대조가 덮지 않는다.
    그 밖에는 담당자가 확인한 처리 유형(confirmed)을 그대로 쓴다.
    confirmed가 없으면 재대조 결과로 정한다. 결과의 changed가 true면 재대조로 바뀐 것이다.
    """
    new = (rechecked or {}).get("handling") or decide(rechecked)
    base = dict(confirmed) if confirmed else decide(None)
    if new["type"] == LINK and not base.get("edited"):
        changed = base.get("type") != LINK or base.get("issue_number") != new["issue_number"]
        return {**new, "changed": changed}
    if not confirmed:
        return {**new, "changed": False}
    return {**base, "changed": False}


def _handling(kind: Handling, number: int | None, *, overlap: bool, judged: bool) -> dict[str, Any]:
    return {"type": kind, "issue_number": number, "overlap": overlap, "judged": judged}


def _quotes(ticket: dict[str, Any]) -> list[str]:
    return [q.strip() for q in ticket.get("evidence_quotes") or [] if q and q.strip()]


def _meeting(meeting_date: str, meeting_title: str) -> str:
    return " ".join(x for x in (meeting_date, meeting_title) if x) or "-"


def link_comment(ticket: dict[str, Any], *, meeting_date: str, meeting_title: str) -> str:
    """SAME으로 연결할 때 기존 이슈에 남길 댓글 (협의 11번: 짧은 고정 형식). `add_comment`의 body."""
    d = ticket.get("dedupe") or {}
    lines = [
        "**MeetToTicket: 회의에서 이 이슈와 같은 작업이 다시 언급되었습니다**",
        "",
        f"- 회의: {_meeting(meeting_date, meeting_title)}",
        f"- 할 일 (회의록 원문): {ticket.get('action_text') or '-'}",
    ]
    quotes = _quotes(ticket)
    if quotes:
        lines.append("- 근거:")
        lines += [f"  > {q}" for q in quotes]
    if d.get("reason"):
        lines.append(f"- 판단 사유: {d['reason']}")
    return "\n".join(lines)


def change_section(ticket: dict[str, Any], *, meeting_date: str, meeting_title: str) -> str:
    """CHANGE로 갱신할 때 기존 본문 끝에 덧붙일 섹션 (협의 12번). `update_issue`의 append_section.

    본문을 바꾸지 않고 덧붙이기만 해서, 기존 본문과 이 섹션이 변경 전후 내용이 된다 (REQ-026).
    """
    d = ticket.get("dedupe") or {}
    before, after = d.get("before"), d.get("after")
    lines = [f"## 회의 변경 사항 ({meeting_date or '날짜 없음'})", ""]
    if before or after:
        lines += ["### 변경 전", before or "(기존 이슈 본문을 확인하세요)", "", "### 변경 후",
                  after or ticket.get('action_text') or "-", ""]
    lines += ["### 유지할 내용", "기존 이슈 본문의 이번 변경과 무관한 요구사항과 진행 이력은 유지합니다.", ""]
    lines.append(f"- 할 일 (회의록 원문): {ticket.get('action_text') or '-'}")
    lines.append(f"- 회의: {_meeting(meeting_date, meeting_title)}")
    quotes = _quotes(ticket)
    if quotes:
        lines.append("- 근거:")
        lines += [f"  > {q}" for q in quotes]
    if d.get("reason"):
        lines.append(f"- 판단 사유: {d['reason']}")
    return "\n".join(lines)


def change_note(*, meeting_date: str, meeting_title: str) -> str:
    """갱신 사실을 알리는 댓글 1개 (협의 12번). `update_issue`의 note."""
    return (f"**MeetToTicket: 회의({_meeting(meeting_date, meeting_title)})에서 이 이슈의 변경이 결정되어 "
            "본문 끝에 '회의 변경 사항'을 추가했습니다.**")
