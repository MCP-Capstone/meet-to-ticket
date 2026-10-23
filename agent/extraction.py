"""회의록 추출 결과 조립 (schemas.Extraction).

액션 아이템 표가 있으면 표에서(from_table), 없으면 LLM이 뽑은 행으로(from_llm) 같은 형태를 만든다.
근거 인용은 회의록 원문에 실제로 있는 문장만 남긴다(keep_verbatim).
"""

from __future__ import annotations

from parsing import ParsedSummary
from resolve import normalize_ws, split_names
from schemas import ActionRowOut, ExtractedDecision, ExtractedTodo, Extraction

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


def from_llm(parsed: ParsedSummary, rows: list[ActionRowOut], meeting_id: str, source: str) -> Extraction:
    """LLM이 뽑은 행을 추출 결과로 바꾸고, 원문과 다른 기한·근거에 확인 메모를 붙인다."""
    normalized = normalize_ws(source)
    todos = []
    for i, r in enumerate(rows, start=1):
        todo = ExtractedTodo(work_key=f"{meeting_id}-auto{i}", action=r.action,
                             assignees=split_names(r.assignees), due=r.due)
        if todo.due and normalize_ws(todo.due) not in normalized:
            todo.warnings.append(f"기한 '{todo.due}'이(가) 원문 표현과 달라 확인 필요")
        # 할 일 문장이 원문에 그대로 있으면 그것을 근거로 쓴다. 없으면 초안 작성에서 근거를 찾는다
        todo.evidence_quotes, _ = keep_verbatim([r.action], source)
        todos.append(todo)
    return Extraction(source="llm", todos=todos, decisions=_decisions(parsed),
                      open_questions=parsed.open_questions, excluded=parsed.excluded)
