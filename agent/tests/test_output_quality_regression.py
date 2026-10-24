"""공식 평가셋을 변경하지 않는 출력·중복 판정 보완 회귀 점검."""

from eval.output_quality import audit_dedupe, audit_ticket


SOURCE = "윤경님은 CSV 다운로드를 수정하고, 결과가 0건이면 헤더만 내려주세요."
REFERENCE = {"title": "CSV 다운로드 규격", "url": "https://www.notion.so/csv-policy"}


def test_ticket_audit_accepts_verbatim_evidence_and_registered_reference():
    content = {"evidence_quotes": ["결과가 0건이면 헤더만 내려주세요."],
               "acceptance_criteria": ["조회 결과가 0건이면 헤더만 포함된 CSV를 반환한다."], "references": [REFERENCE]}
    assert audit_ticket(content, SOURCE, [REFERENCE]) == []


def test_ticket_audit_reports_unverifiable_quote_reference_and_vague_completion():
    content = {"evidence_quotes": ["원문에 없는 인용"], "acceptance_criteria": ["검토한다"],
               "references": [{"title": "만든 문서", "url": "https://example.invalid"}]}
    assert audit_ticket(content, SOURCE, [REFERENCE]) == ["회의록 원문과 다른 인용이 있습니다", "모호한 완료 조건이 있습니다", "확인되지 않은 외부 참고자료가 있습니다"]


def test_dedupe_audit_reports_open_same_that_would_create_duplicate():
    assert audit_dedupe({"relation": "SAME", "issue_number": 42, "issue_state": "open"}, {"type": "CREATE", "issue_number": None}) == ["열린 SAME 판정이 LINK 처리로 연결되지 않았습니다"]


def test_dedupe_audit_accepts_open_change_update():
    assert audit_dedupe({"relation": "CHANGE", "issue_number": 42, "issue_state": "open"}, {"type": "UPDATE", "issue_number": 42}) == []
