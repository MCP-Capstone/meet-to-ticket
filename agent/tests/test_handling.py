"""판정별 처리 유형 규칙과 연결, 갱신 문안 (기획 문서 1.7.2 처리 유형 표, TASK-043)."""

import pytest

from handling import change_note, change_section, decide, link_comment


def judged(relation, number=None, state=None, **extra):
    return {"relation": relation, "issue_number": number, "issue_state": state, "reason": "사유", **extra}


@pytest.mark.parametrize("dedupe, expected", [
    (judged("SAME", 3, "open"), ("LINK", 3, False, True)),
    (judged("CHANGE", 3, "open"), ("UPDATE", 3, False, True)),
    (judged("SAME", 3, "closed"), ("CREATE", 3, True, True)),
    (judged("CHANGE", 3, "closed"), ("CREATE", 3, True, True)),
    (judged("RELATED", 3, "open"), ("CREATE", 3, True, True)),
    (judged("RELATED", 3, "closed"), ("CREATE", 3, True, True)),
    (judged("UNRELATED"), ("CREATE", None, False, True)),
    ({"relation": None, "skipped": "timeout"}, ("CREATE", None, False, False)),
    (None, ("CREATE", None, False, False)),
])
def test_handling_table(dedupe, expected):
    h = decide(dedupe)
    assert (h["type"], h["issue_number"], h["overlap"], h["judged"]) == expected


def test_related_without_number_is_plain_create():
    assert decide(judged("RELATED", None, None)) == {"type": "CREATE", "issue_number": None, "overlap": False, "judged": True}


TICKET = {
    "action_text": "CSV 내보내기를 비동기로 바꾼다",
    "evidence_quotes": ["민규: 동기로 하면 타임아웃 나요", "  "],
    "dedupe": judged("CHANGE", 12, "open", before="동기 처리", after="비동기 처리"),
}


def test_link_comment_format():
    text = link_comment({**TICKET, "dedupe": judged("SAME", 12, "open")},
                        meeting_date="2026-10-23", meeting_title="CSV 점검")
    assert text.splitlines() == [
        "**MeetToTicket: 회의에서 이 이슈와 같은 작업이 다시 언급되었습니다**",
        "",
        "- 회의: 2026-10-23 CSV 점검",
        "- 할 일 (회의록 원문): CSV 내보내기를 비동기로 바꾼다",
        "- 근거:",
        "  > 민규: 동기로 하면 타임아웃 나요",
        "- 판단 사유: 사유",
    ]


def test_change_section_shows_before_and_after():
    text = change_section(TICKET, meeting_date="2026-10-23", meeting_title="CSV 점검")
    assert text.startswith("## 회의 변경 사항 (2026-10-23)\n")
    assert "### 변경 전\n동기 처리" in text
    assert "### 변경 후\n비동기 처리" in text
    assert "### 유지할 내용" in text
    assert "  > 민규: 동기로 하면 타임아웃 나요" in text


def test_change_section_without_values_skips_change_line():
    ticket = {**TICKET, "dedupe": judged("CHANGE", 12, "open"), "evidence_quotes": []}
    text = change_section(ticket, meeting_date="", meeting_title="")
    assert text.startswith("## 회의 변경 사항 (날짜 없음)")
    assert "- 변경:" not in text and "- 근거:" not in text and "- 회의: -" in text


def test_change_note_mentions_meeting():
    assert "회의(2026-10-23 CSV 점검)" in change_note(meeting_date="2026-10-23", meeting_title="CSV 점검")


def test_after_recheck_without_confirmed_uses_recheck():
    from handling import after_recheck

    rechecked = {**judged("CHANGE", 3, "open"), "handling": decide(judged("CHANGE", 3, "open"))}
    assert after_recheck(None, rechecked) == {"type": "UPDATE", "issue_number": 3, "overlap": False,
                                              "judged": True, "changed": False}
    assert after_recheck(None, None)["type"] == "CREATE"
