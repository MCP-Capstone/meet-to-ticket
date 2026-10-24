"""참고 문서 표시 검증 (#335): 배경/설명에 문서 내용이 실제로 쓰인 문서만 참고 문서로 남긴다."""

from references import reflects_document

TITLE = "감사 로그 기획"
DOC = ("# 감사 로그 기획\n\n## 목적\n감사 로그는 연 1회 개인정보보호 점검에 대응하기 위해, 관리자가 사용자별 접근 이력을 추적하는 화면이다. "
       "로그는 1년 동안 보관한다.\n\n## 필터 (전체 계획)\n- 기간 필터\n- 사용자 필터\n- 이벤트 유형 필터")
MEETING = ("관리자 감사 로그 화면에서 날짜를 지정해 조회할 수 없다는 고객 문의가 반복되고 있다. "
           "임나윤은 감사 로그 화면에 기간 필터(시작일, 종료일)를 추가하고, 기간을 지정하지 않으면 최근 7일을 보여 주도록 한다.")


def test_document_fact_in_background_counts():
    background = "관리자 감사 로그 화면에서 날짜별 조회가 안 된다는 문의가 반복된다. 이 화면은 연 1회 개인정보보호 점검에 대응하기 위한 것이다."
    assert reflects_document(DOC, TITLE, background, MEETING)


def test_restating_the_meeting_does_not_count():
    background = "관리자 감사 로그 화면에서 날짜를 지정해 조회할 수 없다는 고객 문의가 반복되고 있다. 이에 기간 필터를 추가하고 기본 조회 기간을 최근 7일로 정한다."
    assert not reflects_document(DOC, TITLE, background, MEETING)


def test_title_alone_does_not_count():
    assert not reflects_document(DOC, TITLE, "감사 로그 기획 문서를 참고해 기간 필터를 추가한다.", MEETING)


def test_spacing_differences_are_ignored():
    assert reflects_document(DOC, TITLE, "개인정보 보호 점검 대응을 위해 접근 이력을 추적한다.", MEETING)


def test_phrase_that_is_also_in_the_meeting_does_not_count():
    # 기획 문서에 있는 구절이라도 회의록에도 있으면 문서를 읽었다는 증거가 아니다
    doc = "# 첨부 정책\n\n기간 필터는 시작일과 종료일을 받는다."
    assert not reflects_document(doc, "첨부 정책", "기간 필터는 시작일과 종료일을 받는다.", "기간 필터는 시작일과 종료일을 받는다고 했다.")


def test_number_with_unit_counts():
    doc = "# 첨부 파일 정책\n\n업로드 파일은 한 개당 최대 50MB까지 허용한다."
    assert reflects_document(doc, "첨부 파일 정책", "업로드 파일 한도는 50MB이다.", "용량 한도를 넘으면 오류 메시지를 보여 준다.")


def test_document_without_body_cannot_be_judged():
    assert reflects_document("", TITLE, "아무 배경", MEETING)
    assert reflects_document("# 감사 로그 기획", TITLE, "아무 배경", MEETING)
