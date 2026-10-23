import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_state, sample
from extraction import MAX_QUOTES, from_llm, from_table, keep_verbatim
from nodes import AgentNodes
from parsing import parse_summary
from resolve import normalize_ws
from schemas import ActionRowOut, Extraction

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
    ex = from_llm(parse_summary(NO_TABLE), rows, "x", NO_TABLE)
    assert isinstance(ex, Extraction) and ex.source == "llm"
    first, second = ex.todos
    assert first.work_key == "x-auto1" and first.assignees == ["박서연", "김민준"]
    assert first.evidence_quotes == ["로그 정리"] and first.warnings == []
    # 원문에 없는 할 일 문장은 근거로 쓰지 않고, 원문과 다른 기한은 확인 메모를 붙인다
    assert second.evidence_quotes == [] and second.assignees == []
    assert second.warnings == ["기한 '다음 주 수요일'이(가) 원문 표현과 달라 확인 필요"]
    assert [d.content for d in ex.decisions] == ["로그는 30일 보관한다."]
    assert ex.open_questions == ["보관 기간을 늘릴지"]


def test_table_and_llm_results_have_same_fields():
    table = from_table(parse_summary(sample("10min_1"))).model_dump()
    llm = from_llm(parse_summary(NO_TABLE), [ActionRowOut(action="로그 정리", assignees="", due="")], "x", NO_TABLE).model_dump()
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
