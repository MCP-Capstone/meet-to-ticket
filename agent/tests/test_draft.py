"""초안 작성 노드 (TASK-017): 추출 결과 반영, 미결 질문 제한, 빠진 초안 재작성, 카드 경고, 이슈 본문."""

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_state, run_review
from extraction import NO_EVIDENCE_WARNING
from nodes import AgentNodes, new_ticket
from publisher import build_issue_body
from schemas import DraftTicket, ExtractedTodo

pytestmark = pytest.mark.anyio

Q1 = "IAM-48이 실제 권한 세분화 이슈인지"


def by_key(state):
    return {t["work_key"]: t for t in state["tickets"]}


async def test_draft_receives_extraction(settings):
    llm = FakeLLM()
    await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), make_state())
    context = llm.draft_contexts[0]
    assert len(context["decisions"]) == 6 and context["open_questions"][0] == Q1
    # 할 일마다 추출 단계에서 원문과 대조한 근거를 함께 넘긴다
    assert all(it["evidence_quotes"] == [it["action"]] for it in llm.draft_calls[0])


async def test_open_questions_limited_to_extracted(settings):
    llm = FakeLLM(draft_questions=["IAM-48이 실제  권한 세분화 이슈인지", Q1, "지어낸 질문"])
    state, _ = await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), make_state())
    # 공백만 다른 질문은 추출된 원문 표현으로 바꾸고, 목록에 없는 질문은 뺀다
    assert all(t["open_questions"] == [Q1] for t in state["tickets"])


async def test_missing_draft_is_written_again(settings):
    llm = FakeLLM(skip_draft={"10m1-a1": 1})
    state, _ = await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), make_state())
    a1 = by_key(state)["10m1-a1"]
    assert a1["title"] and a1["review_status"] == "APPROVED" and a1["revisions"] == 1
    retry = [it for call in llm.draft_calls[1:] for it in call]
    assert [it["work_key"] for it in retry] == ["10m1-a1"] and "current_draft" not in retry[0]


async def test_missing_revise_draft_is_not_reviewed_again(settings):
    """보완 중이던 초안이 빠지면 이전 초안을 다시 검수하지 않고 같은 피드백으로 다시 쓴다."""
    llm = FakeLLM(review_plan={"10m1-a1": [[(4, "원문에 없음")]]}, skip_revise={"10m1-a1": 1})
    state, _ = await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), make_state())
    a1 = by_key(state)["10m1-a1"]
    assert llm.review_round["10m1-a1"] == 2  # 첫 초안, 다시 쓴 초안만 검수
    assert a1["revisions"] == 2 and a1["review_status"] == "APPROVED" and a1["title"].endswith("(수정)")
    retries = [it for call in llm.draft_calls[1:] for it in call if it["work_key"] == "10m1-a1"]
    assert all(it["feedback"] == retries[0]["feedback"] for it in retries) and len(retries) == 2


async def test_draft_missing_until_limit_falls_back(settings):
    llm = FakeLLM(skip_draft={"10m1-a1": 99})
    state, _ = await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), make_state())
    a1 = by_key(state)["10m1-a1"]
    assert a1["revisions"] == settings.max_revisions
    assert a1["fallback"] is True and a1["title"] == a1["action_text"]


def draft(**kw):
    base = dict(work_key="k", title="제목", description="설명", tasks=[], acceptance_criteria=["조건"],
                evidence_quotes=[], open_questions=[], labels=[])
    return DraftTicket(**{**base, **kw})


def test_draft_evidence_clears_extraction_warning():
    t = new_ticket(ExtractedTodo(work_key="k", action="지어낸 할 일", warnings=[NO_EVIDENCE_WARNING]), [], None, "llm")
    AgentNodes._apply_draft(t, draft(evidence_quotes=["없는 문장"]), [], "원문 문장이다.", [])
    # 초안 근거도 원문에 없으면 근거를 비워 두고 경고를 유지한다 (할 일 문장을 근거로 꾸미지 않음)
    assert t["evidence_quotes"] == [] and NO_EVIDENCE_WARNING in t["warnings"]
    AgentNodes._apply_draft(t, draft(evidence_quotes=["원문 문장이다."]), [], "원문 문장이다.", [])
    assert t["evidence_quotes"] == ["원문 문장이다."] and NO_EVIDENCE_WARNING not in t["warnings"]


def test_draft_warnings_do_not_pile_up():
    t = new_ticket(ExtractedTodo(work_key="k", action="할 일", evidence_quotes=["할 일"],
                                 warnings=["원문과 다른 근거 인용 1개를 제외함"]), [], None, "llm")
    d = draft(evidence_quotes=["없는 문장"], labels=["없는 라벨"])
    for _ in range(3):  # 보완으로 초안을 여러 번 다시 써도
        AgentNodes._apply_draft(t, d, ["backend"], "할 일", [])
    # 추출 단계와 초안 단계 경고가 구분되고, 초안 단계 경고는 한 번만 남는다
    assert t["warnings"] == ["원문과 다른 근거 인용 1개를 제외함",
                             "라벨 후보에 없어 뺀 라벨: 없는 라벨",
                             "초안의 근거 인용 중 원문과 다른 1개를 제외함"]
    assert t["evidence_quotes"] == ["할 일"]


async def test_result_has_issue_body(settings):
    nodes = AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings)
    state, _ = await run_review(nodes, make_state())
    result = nodes.build_result(state)
    item = result["items"][0]
    t = state["tickets"][0]
    assert item["content_json"]["issue_body"] == build_issue_body(t, state["meeting_id"], state["background"])
    assert item["content_json"]["issue_body"].startswith("## 작업 내용\n")
