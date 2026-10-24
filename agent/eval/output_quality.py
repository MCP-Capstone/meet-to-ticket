"""출력 형식 변경의 안전 회귀 점검."""

from __future__ import annotations

from typing import Any


def audit_ticket(content: dict[str, Any], original_content: str,
                 available_references: list[dict[str, str]] | None = None) -> list[str]:
    """저장 티켓의 인용, 참고 자료, 완료 조건에 대한 정적 경고 목록을 돌려준다."""
    warnings: list[str] = []
    quotes = [str(quote).strip() for quote in content.get("evidence_quotes") or [] if str(quote).strip()]
    if not quotes:
        warnings.append("회의록 근거가 없습니다")
    elif any(quote not in original_content for quote in quotes):
        warnings.append("회의록 원문과 다른 인용이 있습니다")
    criteria = [str(item).strip() for item in content.get("acceptance_criteria") or [] if str(item).strip()]
    if not criteria:
        warnings.append("검증 가능한 완료 조건이 없습니다")
    if any(item in {"확인한다", "검토한다", "처리한다", "개선한다"} for item in criteria):
        warnings.append("모호한 완료 조건이 있습니다")
    allowed = {(item.get("title"), item.get("url")) for item in available_references or []}
    for reference in content.get("references") or []:
        pair = (reference.get("title"), reference.get("url")) if isinstance(reference, dict) else (None, None)
        if pair not in allowed:
            warnings.append("확인되지 않은 외부 참고자료가 있습니다")
            break
    return warnings


def audit_dedupe(dedupe: dict[str, Any] | None, handling: dict[str, Any] | None) -> list[str]:
    """중복 판정과 확정 처리 유형의 최소 계약을 확인한다."""
    dedupe, handling = dedupe or {}, handling or {}
    relation, number = dedupe.get("relation"), dedupe.get("issue_number")
    kind, target = handling.get("type"), handling.get("issue_number")
    warnings: list[str] = []
    if relation in {"SAME", "CHANGE", "RELATED"} and not number:
        warnings.append("관련 이슈 판정에 대상 이슈 번호가 없습니다")
    if relation == "SAME" and dedupe.get("issue_state") == "open" and kind != "LINK":
        warnings.append("열린 SAME 판정이 LINK 처리로 연결되지 않았습니다")
    if relation == "CHANGE" and dedupe.get("issue_state") == "open" and kind != "UPDATE":
        warnings.append("열린 CHANGE 판정이 UPDATE 처리로 연결되지 않았습니다")
    if kind in {"LINK", "UPDATE"} and not target:
        warnings.append("LINK 또는 UPDATE 처리에 대상 이슈 번호가 없습니다")
    return warnings
