import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_state, run_review
from nodes import AgentNodes, new_ticket
from parsing import ActionRow
from schemas import ActionRowOut

pytestmark = pytest.mark.anyio


async def run_case(settings, state=None, llm=None):
    nodes = AgentNodes(llm or FakeLLM(), FakePublisher(), FakeReporter(), settings)
    return await run_review(nodes, state or make_state())


def by_key(state):
    return {t["work_key"]: t for t in state["tickets"]}


async def test_10min_review(settings):
    state, nxt = await run_case(settings)
    tickets = by_key(state)
    assert nxt == "publish"
    assert len(tickets) == 8
    # 조건부 기존 이슈 갱신(a2, a8)은 확인 필요, 나머지 6건 승인
    for key in ("10m1-a2", "10m1-a8"):
        assert tickets[key]["review_status"] == "NEEDS_CHECK"
        assert tickets[key]["action"] == "NEEDS_CHECK"
    approved = [t for t in state["tickets"] if t["review_status"] == "APPROVED"]
    assert len(approved) == 6 and all(t["action"] == "CREATE" for t in approved)
    # 다중 담당자: 정하늘은 github_name이 없어 비움
    a7 = tickets["10m1-a7"]
    assert a7["assignees"] == ["minjun-k", "seoyeon-p", "doyun-l", "yujin-c", "jihoon-o"]
    assert a7["unresolved_assignees"] == ["정하늘"]
    # 기한 계산
    assert tickets["10m1-a2"]["due"] == {"text": "2026-10-20 오전", "date": "2026-10-20", "time": "12:00"}


async def test_conditional_approved_when_setting_off(settings):
    settings.conditional_as_needs_check = False
    state, _ = await run_case(settings)
    assert by_key(state)["10m1-a2"]["review_status"] == "APPROVED"


async def test_revise_then_pass(settings):
    llm = FakeLLM(review_plan={"10m1-a1": [[(4, "원문에 없는 표현")]]})
    state, _ = await run_case(settings, llm=llm)
    a1 = by_key(state)["10m1-a1"]
    assert a1["review_status"] == "APPROVED"
    assert a1["revisions"] == 1
    assert a1["title"].endswith("(수정)")
    # 두 번째 초안 호출에는 a1만, 피드백과 함께 들어감
    assert [it["work_key"] for it in llm.draft_calls[1]] == ["10m1-a1"]
    assert llm.draft_calls[1][0]["feedback"] == ["[4] '가나다라': 원문에 없는 표현 → 고친다"]


async def test_revise_limit_falls_back_to_default_draft(settings):
    llm = FakeLLM(review_plan={"10m1-a3": [[(4, "원문에 없는 표현")]]}, always={"10m1-a3"})
    state, _ = await run_case(settings, llm=llm)
    a3 = by_key(state)["10m1-a3"]
    assert a3["review_status"] == "APPROVED"
    assert a3["fallback"] is True
    assert a3["revisions"] == 2
    assert a3["description"].startswith("회의에서 정한 액션 아이템:")
    assert a3["review_notes"]


async def test_revise_limit_needs_check_when_fallback_off(settings):
    settings.fallback_publish = False
    llm = FakeLLM(review_plan={"10m1-a3": [[(4, "원문에 없는 표현")]]}, always={"10m1-a3"})
    state, _ = await run_case(settings, llm=llm)
    a3 = by_key(state)["10m1-a3"]
    assert a3["review_status"] == "NEEDS_CHECK"
    assert a3["reason"].startswith("보완 2회 후에도 미통과")


async def test_table_ticket_not_rejected_but_revised(settings):
    # 표에서 온 티켓은 2·3번 실패를 반려하지 않고 보완으로 돌린다
    llm = FakeLLM(review_plan={"10m1-a4": [[(2, "제외 항목 내용이 섞임")]]})
    state, _ = await run_case(settings, llm=llm)
    a4 = by_key(state)["10m1-a4"]
    assert a4["review_status"] == "APPROVED"
    assert a4["revisions"] == 1
    ctx = llm.review_contexts[0]
    assert "알림 센터 연동" in ctx["excluded_or_held"]


async def test_llm_extracted_ticket_not_rejected_by_single_review(settings):
    content = "# 회의 요약\n\n- 회의 ID: z\n- 회의 일시: 2026-10-19 10:00\n\n## 논의\n\n박서연이 로그를 정리하기로 했다.\n"
    llm = FakeLLM(rows=[ActionRowOut(action="로그 정리", assignees="박서연", due="")],
                  review_plan={"z-auto1": [[(3, "할 일이 아닌 것 같음")]]})
    state, _ = await run_case(settings, state=make_state("z", content), llm=llm)
    assert state["tickets"][0]["review_status"] == "APPROVED"


async def test_llm_extracted_ticket_persistent_scope_doubt_needs_check(settings):
    content = "# 회의 요약\n\n- 회의 ID: z\n- 회의 일시: 2026-10-19 10:00\n\n## 논의\n\n의견만 나눴다.\n"
    llm = FakeLLM(rows=[ActionRowOut(action="의견 정리", assignees="", due="")],
                  review_plan={"z-auto1": [[(3, "할 일이 아니라 의견임")]]}, always={"z-auto1"})
    state, _ = await run_case(settings, state=make_state("z", content), llm=llm)
    assert state["tickets"][0]["review_status"] == "NEEDS_CHECK"


async def test_only_missing_action_item_is_rejected(settings):
    nodes = AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings)
    t = new_ticket(ActionRow("", "", "", "", ""), [], None)
    t.update(title="제목", description="설명", acceptance_criteria=["완료"])
    nodes._decide(t, nodes._code_checks(t, None))
    assert t["review_status"] == "REJECTED"
    # 2·3번 지적은 반려가 아니라 보완
    t2 = new_ticket(ActionRow("k", "할 일", "", "", ""), [], None)
    nodes._decide(t2, [{"item": 2, "problem": "제외 항목", "fix": "뺀다"}, {"item": 3, "problem": "할 일 아님", "fix": "-"}])
    assert t2["review_status"] == "REVISE"


async def test_bad_quote_is_dropped_not_looped(settings):
    llm = FakeLLM(bad_quote_for={"10m1-a5"})
    state, _ = await run_case(settings, llm=llm)
    a5 = by_key(state)["10m1-a5"]
    assert a5["review_status"] == "APPROVED"
    assert a5["revisions"] == 0
    assert a5["evidence_quotes"] == [a5["action_text"]]
    assert any("근거 인용" in w for w in a5["warnings"])


@pytest.mark.parametrize("where", ["draft", "review"])
async def test_llm_error_stops(settings, where):
    state, nxt = await run_case(settings, llm=FakeLLM(fail_on=where))
    assert nxt == "finish"
    assert state["error_code"] == "LLM_ERROR"


async def test_no_table_uses_llm_rows(settings):
    content = "# 회의 요약\n\n- 회의 ID: x\n- 회의 일시: 2026-10-19 10:00\n\n## 논의\n\n박서연이 내일까지 로그 정리를 하기로 했다.\n"
    llm = FakeLLM(rows=[ActionRowOut(action="로그 정리", assignees="박서연", due="내일")])
    state, _ = await run_case(settings, state=make_state("x", content), llm=llm)
    t = state["tickets"][0]
    assert t["work_key"] == "x-auto1" and t["source"] == "llm"
    assert t["due"]["date"] == "2026-10-20"
    assert t["review_status"] == "APPROVED"


async def test_no_tickets(settings):
    content = "# 회의 요약\n\n- 회의 ID: y\n\n## 논의\n\n잡담만 했다.\n"
    state, nxt = await run_case(settings, state=make_state("y", content), llm=FakeLLM(rows=[]))
    assert nxt == "finish"
    assert state["error_code"] == "NO_TICKETS"
    assert state["tickets"] == []


async def test_30min_hold_and_keys(settings):
    state, _ = await run_case(settings, state=make_state("30min_1"))
    tickets = by_key(state)
    assert len(tickets) == 13
    assert tickets["30m1-a13"]["review_status"] == "NEEDS_CHECK"
    assert "발행 보류" in tickets["30m1-a13"]["reason"]
    assert tickets["30m1-a1"]["expected"]["keys"] == ["AUTH-112"]
    assert sum(t["review_status"] == "APPROVED" for t in state["tickets"]) == 12


async def test_60min_all_24(settings):
    state, _ = await run_case(settings, state=make_state("60min_1"))
    assert len(state["tickets"]) == 24
    assert all(t["review_status"] == "APPROVED" for t in state["tickets"])
    assert by_key(state)["60min_1-a1"]["due"]["date"] == "2026-10-28"


async def test_advisory_items_do_not_block(settings):
    llm = FakeLLM(review_plan={"10m1-a1": [[(7, "제목이 길다"), (8, "완료 조건 추가 제안")]]}, always={"10m1-a1"})
    state, _ = await run_case(settings, llm=llm)
    a1 = by_key(state)["10m1-a1"]
    assert a1["review_status"] == "APPROVED" and a1["revisions"] == 0
    assert len(a1["review_notes"]) == 2


async def test_unverifiable_findings_are_ignored(settings):
    from schemas import ReviewCheck, TicketReview

    class SloppyLLM(FakeLLM):
        async def review(self, summary, drafts, context=None):
            out = []
            for d in drafts:
                out.append(TicketReview(work_key=d["work_key"], checks=[
                    ReviewCheck(item=2, passed=False, quote="", problem="제외 항목 없음", fix="필요 없음"),
                    ReviewCheck(item=4, passed=False, quote=d["action"], problem="원문에 없음", fix="삭제"),
                    ReviewCheck(item=3, passed=False, quote="초안에 없는 문구", problem="할 일 아님", fix="-"),
                ]))
            return out

    state, _ = await run_case(settings, llm=SloppyLLM())
    approved = [t for t in state["tickets"] if t["review_status"] == "APPROVED"]
    assert len(approved) == 6
    assert all(t["revisions"] == 0 for t in approved)
