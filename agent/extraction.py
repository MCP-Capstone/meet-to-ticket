"""회의록 추출 결과 조립 (schemas.Extraction).

액션 아이템 표가 있으면 표에서(from_table), 없으면 LLM 추출 결과로(from_llm) 같은 형태를 만든다.
근거 인용은 회의록 원문에 실제로 있는 문장만 남긴다(keep_verbatim).
"""

from __future__ import annotations

from parsing import ParsedSummary
from resolve import normalize_ws, split_names
from schemas import ActionRowsOut, ExtractedDecision, ExtractedTodo, Extraction

MAX_QUOTES = 3


def keep_verbatim(quotes: list[str], source: str) -> tuple[list[str], int]:
    """원문(공백 정규화 기준)에 있는 인용만 최대 MAX_QUOTES개 남기고, 뺀 개수를 함께 돌려준다."""
    normalized = normalize_ws(source)
    cleaned = [q.strip() for q in quotes if q.strip()]
    kept = [q for q in cleaned if normalize_ws(q) in normalized]
    return kept[:MAX_QUOTES], len(cleaned) - len(kept)


def _decisions(parsed: ParsedSummary) -> list[ExtractedDecision]:
    # 결정 사항 목록의 각 줄이 그대로 원문 인용이다
    return [ExtractedDecision(content=d, evidence_quotes=[d]) for d in parsed.decisions]


def from_table(parsed: ParsedSummary) -> Extraction:
    todos = [
        ExtractedTodo(
            work_key=r.work_key,
            action=r.action,
            assignees=split_names(r.assignees_raw),
            due=r.due_raw,
            evidence_quotes=[r.action],  # 표의 액션 칸은 원문 그대로다
            expected=r.expected_raw,
        )
        for r in parsed.rows
    ]
    return Extraction(source="table", todos=todos, decisions=_decisions(parsed),
                      open_questions=parsed.open_questions, excluded=parsed.excluded)


def from_llm(parsed: ParsedSummary, out: ActionRowsOut, meeting_id: str, source: str) -> Extraction:
    """LLM 추출 결과를 추출 결과로 바꾼다.

    근거 인용은 원문에 있는 것만 남기고, 원문과 다른 기한·근거에는 확인 메모를 붙인다.
    결정·미결 질문·제외 항목은 회의록에 해당 섹션이 있으면 코드로 읽은 값을, 없으면 LLM 값을 쓴다.
    """
    normalized = normalize_ws(source)
    todos = []
    for i, r in enumerate(out.rows, start=1):
        todo = ExtractedTodo(work_key=f"{meeting_id}-auto{i}", action=r.action,
                             assignees=split_names(r.assignees), due=r.due)
        if todo.due and normalize_ws(todo.due) not in normalized:
            todo.warnings.append(f"기한 '{todo.due}'이(가) 원문 표현과 달라 확인 필요")
        quotes, dropped = keep_verbatim(r.evidence_quotes, source)
        if dropped:
            todo.warnings.append(f"원문과 다른 근거 인용 {dropped}개를 제외함")
        if not quotes:
            # 할 일 문장이 원문에 그대로 있으면 그것을 근거로 쓴다
            quotes, _ = keep_verbatim([r.action], source)
        if not quotes:
            todo.warnings.append("회의록 원문에서 근거를 찾지 못해 확인 필요")
        todo.evidence_quotes = quotes
        todos.append(todo)
    decisions = _decisions(parsed) or [
        ExtractedDecision(content=d.content.strip(), evidence_quotes=keep_verbatim(d.evidence_quotes, source)[0])
        for d in out.decisions if d.content.strip()
    ]
    return Extraction(
        source="llm",
        todos=todos,
        decisions=decisions,
        open_questions=parsed.open_questions or _clean(out.open_questions),
        excluded=parsed.excluded or _clean(out.excluded),
    )


def _clean(items: list[str]) -> list[str]:
    return [x.strip() for x in items if x.strip()]
