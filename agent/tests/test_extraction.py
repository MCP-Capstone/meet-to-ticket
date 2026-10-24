import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_state, sample
from extraction import MAX_QUOTES, from_llm, from_table, keep_verbatim
from nodes import AgentNodes
from parsing import parse_summary
from resolve import normalize_ws
from schemas import ActionRowOut, ActionRowsOut, DecisionOut, Extraction

NO_TABLE = (
    "# 회의 요약\n\n- 회의 ID: x\n- 회의 일시: 2026-10-19 10:00\n\n"
    "## 핵심 결정\n\n- 로그는 30일 보관한다.\n\n"
    "## 논의\n\n박서연이 내일까지 로그 정리를 하기로 했다.\n\n"
    "## 미결 질문\n\n- 보관 기간을 늘릴지\n"
)


@pytest.mark.parametrize("name,count", [("10min_1", 6), ("30min_1", 7), ("60min_1", 12)])
def test_decisions_count(name, count):
    assert len(parse_summary(sample(name)).decisions) == count


def test_table_extraction():
    text = sample("10min_1")
    ex = from_table(parse_summary(text))
    assert ex.source == "table"
    assert len(ex.todos) == 8 and len(ex.decisions) == 6 and len(ex.open_questions) == 2
    a7 = next(t for t in ex.todos if t.work_key == "10m1-a7")
    assert a7.assignees == ["김민준", "박서연", "이도윤", "최유진", "정하늘", "오지훈"]
    assert a7.due == "2026-10-22 14:00"
    assert a7.expected == "신규 티켓"
    assert ex.decisions[0].content == "1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다."
    assert ex.excluded == ["알림 센터 연동", "일반 관리자용 역할 설정 화면의 세부 export 권한"]


def test_paragraph_decisions_survive_table_extraction_with_verbatim_evidence():
    text = (
        "# 운영 회의\n\n## 결정사항\n"
        "0건 응답은 HTTP 200으로 유지한다.\n헤더 행은 포함한다.\n\n"
        "운영 가이드는 Notion으로 공유한다.\n\n"
        "## 액션 아이템\n| ID | 할 일 | 담당자 | 기한 |\n| --- | --- | --- | --- |\n"
        "| a1 | 0건 통합 테스트 추가 | 박서연 | 2026-10-30 |\n\n"
        "## 미결 질문\n- 적용 시점은?\n\n## 제외\n- PDF 구현\n"
    )
    ex = from_table(parse_summary(text))
    assert [d.content for d in ex.decisions] == [
        "0건 응답은 HTTP 200으로 유지한다.\n헤더 행은 포함한다.",
        "운영 가이드는 Notion으로 공유한다.",
    ]
    assert len(ex.todos) == 1 and ex.todos[0].work_key == 'a1'
    assert ex.open_questions == ['적용 시점은?'] and ex.excluded == ['PDF 구현']
    assert all(q in text for d in ex.decisions for q in d.evidence_quotes)


def test_mixed_decision_paragraphs_and_lists_keep_order():
    parsed = parse_summary('## 핵심 결정\n문서로 공유한다.\n\n- 기존 결정\n1. 첫 순서\n2) 두 번째 순서\n\n별도 명시 없음\n')
    assert parsed.decisions == ['문서로 공유한다.', '기존 결정', '첫 순서', '두 번째 순서']


def test_decision_section_ignores_headings_tables_and_rules():
    parsed = parse_summary(
        '## 결정사항\n### 일정\n- 11월에 출시한다.\n\n---\n| 항목 | 결정 |\n| --- | --- |\n| 범위 | CSV |\n\n'
        '***\n문서로 공유한다.\n'
    )
    assert parsed.decisions == ['11월에 출시한다.', '문서로 공유한다.']


def test_indented_continuation_line_belongs_to_previous_decision():
    text = '## 결정사항\n- 결정 A\n  (이유: 일정)\n- 결정 B\n2. 결정 C\n   상세 설명\n바로 이어진 문단\n'
    ex = from_table(parse_summary(text))
    assert [d.content for d in ex.decisions] == [
        '결정 A\n  (이유: 일정)', '결정 B', '결정 C\n   상세 설명', '바로 이어진 문단',
    ]
    assert all(q in text for d in ex.decisions for q in d.evidence_quotes)


@pytest.mark.parametrize('line', ['2026. 11월에 출시한다.', '11. 3. 출시로 확정한다.'])
def test_sentence_starting_with_date_is_not_numbered_list(line):
    parsed = parse_summary(f'## 결정\n{line}\n')
    assert parsed.decisions == [line]


def test_real_numbered_list_keeps_number_stripped():
    parsed = parse_summary('## 결정\n1. 2단계로 나눠 출시한다.\n10) 열 번째 결정\n')
    assert parsed.decisions == ['2단계로 나눠 출시한다.', '열 번째 결정']


@pytest.mark.parametrize('body', ['', '없음', '- 없음', '별도 명시 없음'])
def test_empty_decision_section_stays_empty(body):
    parsed = parse_summary('## 결정\n' + body)
    assert parsed.decisions == [] and 'decisions' in parsed.found


@pytest.mark.anyio
async def test_paragraph_decisions_reach_slack_notice_without_extra_tickets(settings):
    text = sample('10min_1').replace('- 1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다.',
                                   '1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다.')
    nodes = AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings)
    state = make_state('10min_1', text)
    state.update(await nodes.prepare(state))
    notice = nodes._slack_notice(state, state['tickets'])
    assert '1차 릴리스에서는 조직 관리자만 CSV 내보내기를 사용할 수 있다.' in notice['content_json']['slack']['text']
    assert len(state['tickets']) == 8


@pytest.mark.parametrize("name", ["10min_1", "30min_1", "60min_1"])
def test_evidence_quotes_are_verbatim(name):
    text = sample(name)
    ex = from_table(parse_summary(text))
    src = normalize_ws(text)
    quotes = [q for t in ex.todos for q in t.evidence_quotes] + [q for d in ex.decisions for q in d.evidence_quotes]
    assert quotes and all(normalize_ws(q) in src for q in quotes)
    assert all(1 <= len(t.evidence_quotes) <= MAX_QUOTES for t in ex.todos)


def test_llm_extraction_same_schema():
    rows = [
        ActionRowOut(action="로그 정리", assignees="박서연, 김민준", due="내일"),
        ActionRowOut(action="원문에 없는 할 일", assignees="", due="다음 주 수요일"),
    ]
    ex = from_llm(parse_summary(NO_TABLE), ActionRowsOut(rows=rows), "x", NO_TABLE)
    assert isinstance(ex, Extraction) and ex.source == "llm"
    first, second = ex.todos
    assert first.work_key == "x-auto1" and first.assignees == ["박서연", "김민준"]
    assert first.evidence_quotes == ["로그 정리"] and first.warnings == []
    # 원문에 없는 할 일 문장은 근거로 쓰지 않고, 원문과 다른 기한은 확인 메모를 붙인다
    assert second.evidence_quotes == [] and second.assignees == []
    assert second.warnings == ["기한 '다음 주 수요일'이(가) 원문 표현과 달라 확인 필요",
                               "회의록 원문에서 근거를 찾지 못해 확인 필요"]
    assert [d.content for d in ex.decisions] == ["로그는 30일 보관한다."]
    assert ex.open_questions == ["보관 기간을 늘릴지"]


def test_table_and_llm_results_have_same_fields():
    table = from_table(parse_summary(sample("10min_1"))).model_dump()
    out = ActionRowsOut(rows=[ActionRowOut(action="로그 정리", assignees="", due="")])
    llm = from_llm(parse_summary(NO_TABLE), out, "x", NO_TABLE).model_dump()
    assert table.keys() == llm.keys()
    assert table["todos"][0].keys() == llm["todos"][0].keys()


def test_keep_verbatim():
    source = "첫 문장이다.\n둘째   문장이다.\n셋째 문장이다.\n넷째 문장이다."
    kept, dropped = keep_verbatim(["둘째 문장이다.", "없는 문장", " ", "첫 문장이다."], source)
    assert kept == ["둘째 문장이다.", "첫 문장이다."] and dropped == 1
    kept, _ = keep_verbatim(["첫 문장이다.", "둘째 문장이다.", "셋째 문장이다.", "넷째 문장이다."], source)
    assert len(kept) == MAX_QUOTES


@pytest.mark.anyio
async def test_prepare_returns_extraction(settings):
    nodes = AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings)
    out = await nodes.prepare(make_state())
    assert len(out["tickets"]) == 8 and len(out["decisions"]) == 6
    assert out["decisions"][0]["evidence_quotes"] == [out["decisions"][0]["content"]]
    assert all(t["evidence_quotes"] == [t["action_text"]] for t in out["tickets"])


@pytest.mark.anyio
async def test_prepare_llm_path_keeps_warnings(settings):
    llm = FakeLLM(rows=[ActionRowOut(action="로그 정리", assignees="박서연", due="모레")])
    nodes = AgentNodes(llm, FakePublisher(), FakeReporter(), settings)
    out = await nodes.prepare(make_state("x", NO_TABLE))
    t = out["tickets"][0]
    assert t["source"] == "llm" and t["assignee_names"] == ["박서연"]
    assert t["warnings"] == ["기한 '모레'이(가) 원문 표현과 달라 확인 필요"]
    assert len(out["decisions"]) == 1


FREE_FORM = sample("csv_check")


def test_free_form_notes_use_llm_extraction():
    """섹션 없는 구간별 메모: 결정·미결 질문·제외 항목을 LLM 결과에서 가져오고 근거는 원문과 대조한다."""
    out = ActionRowsOut(
        rows=[
            ActionRowOut(action="빈 결과 CSV 500 오류 수정", assignees="최윤경", due="내일 오후 5시",
                         evidence_quotes=["최윤경이 내일 오후 5시까지 수정하고 0건·1건 결과를",
                                          "지어낸 근거 문장"]),
            ActionRowOut(action="API 업무 기한 변경", assignees="", due="내일 오후 6시",
                         evidence_quotes=["담당자와 구현 범위는 유지하고, 기한을 내일 오후 6시로"]),
        ],
        decisions=[
            DecisionOut(content="다운로드 이력 화면은 이번 구현에서 제외한다",
                        evidence_quotes=["권한과 보관 방식이 정해지지 않아 이번 구현에서는 제외하기로 했다."]),
            DecisionOut(content="  ", evidence_quotes=[]),
        ],
        open_questions=["권한과 보관 방식이 정해지지 않아", "다운로드 이력의 권한은?", " "],
        excluded=["다운로드 이력 화면", "테스트 데이터 추가 제작"],
    )
    ex = from_llm(parse_summary(FREE_FORM), out, "csv_check", FREE_FORM)
    fix, api = ex.todos
    assert fix.assignees == ["최윤경"] and fix.due == "내일 오후 5시"
    assert fix.evidence_quotes == ["최윤경이 내일 오후 5시까지 수정하고 0건·1건 결과를"]
    assert fix.warnings == ["원문과 다른 근거 인용 1개를 제외함"]
    assert api.evidence_quotes and api.warnings == []
    assert [d.content for d in ex.decisions] == ["다운로드 이력 화면은 이번 구현에서 제외한다"]
    # 여러 줄에 걸친 문장도 공백 정규화로 대조된다
    assert ex.decisions[0].evidence_quotes == ["권한과 보관 방식이 정해지지 않아 이번 구현에서는 제외하기로 했다."]
    # 미결 질문·제외 항목은 원문에 그대로 있는 것만 남는다
    assert ex.open_questions == ["권한과 보관 방식이 정해지지 않아"]
    assert ex.excluded == ["다운로드 이력 화면"]


def test_llm_decision_without_source_evidence_is_dropped():
    out = ActionRowsOut(rows=[], decisions=[
        DecisionOut(content="지어낸 결정", evidence_quotes=["없는 문장"]),
        DecisionOut(content="이번 구현에서는 제외하기로 했다", evidence_quotes=[]),
    ])
    ex = from_llm(parse_summary(FREE_FORM), out, "csv_check", FREE_FORM)
    # 근거가 없으면 빼고, 결정 문장이 원문에 그대로 있으면 그것을 근거로 쓴다
    assert [(d.content, d.evidence_quotes) for d in ex.decisions] == [
        ("이번 구현에서는 제외하기로 했다", ["이번 구현에서는 제외하기로 했다"])]


def test_summary_sections_win_over_llm():
    """회의록에 결정·미결 질문 섹션이 있으면 코드로 읽은 값을 쓴다."""
    out = ActionRowsOut(rows=[], decisions=[DecisionOut(content="LLM 결정", evidence_quotes=[])],
                        open_questions=["LLM 질문"], excluded=["LLM 제외"])
    ex = from_llm(parse_summary(NO_TABLE), out, "x", NO_TABLE)
    assert [d.content for d in ex.decisions] == ["로그는 30일 보관한다."]
    assert ex.open_questions == ["보관 기간을 늘릴지"]
    assert ex.excluded == []  # 제외 섹션이 없어 LLM 값을 쓰지만, 원문에 없는 문구라 빠진다


def test_empty_section_is_not_filled_by_llm():
    """섹션에 "- 없음"만 있으면 회의록이 없다고 한 것이므로 LLM 값을 쓰지 않는다."""
    text = NO_TABLE + "\n## 제외·보류\n\n- 없음\n"
    parsed = parse_summary(text)
    assert parsed.excluded == [] and "excluded" in parsed.found
    out = ActionRowsOut(rows=[], excluded=["로그 정리"])  # 원문에 있는 문구여도
    assert from_llm(parsed, out, "x", text).excluded == []


def test_llm_quote_falls_back_to_action_text():
    out = ActionRowsOut(rows=[ActionRowOut(action="로그 정리", assignees="", due="", evidence_quotes=["없는 문장"])])
    todo = from_llm(parse_summary(NO_TABLE), out, "x", NO_TABLE).todos[0]
    assert todo.evidence_quotes == ["로그 정리"]
    assert todo.warnings == ["원문과 다른 근거 인용 1개를 제외함"]


@pytest.mark.anyio
async def test_prepare_passes_llm_excluded_to_review(settings):
    """자유 형식 회의록에서도 LLM이 뽑은 제외 항목이 검수 2번(제외·보류 항목 아님)의 기준이 된다. 원문에 없는 항목은 빠진다."""
    llm = FakeLLM(rows=[ActionRowOut(action="빈 결과 오류 수정", assignees="최윤경", due="내일 오후 5시")],
                  decisions=[DecisionOut(content="이력 화면 제외", evidence_quotes=["이번 구현에서는 제외하기로 했다."]),
                             DecisionOut(content="근거 없는 결정", evidence_quotes=[])],
                  excluded=["다운로드 이력 화면", "원문에 없는 제외 항목"])
    nodes = AgentNodes(llm, FakePublisher(), FakeReporter(), settings)
    out = await nodes.prepare(make_state("csv_check", FREE_FORM))
    assert out["excluded"] == ["다운로드 이력 화면"]
    assert out["decisions"] == [{"content": "이력 화면 제외", "evidence_quotes": ["이번 구현에서는 제외하기로 했다."]}]
    assert out["tickets"][0]["source"] == "llm"
