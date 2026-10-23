from dataclasses import replace

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request, make_state, run_plain, run_review
from nodes import AgentNodes
from publisher import MAX_LABELS, build_issue_body, pick_labels, related_lines
from runner import initial_state

pytestmark = pytest.mark.anyio

TICKET = {
    "work_key": "10m1-a1",
    "action_text": "비동기 사용자 CSV 내보내기 API와 생성기 구현",
    "description": "필터 결과 전체를 CSV로 내보내는 API를 만든다.",
    "tasks": ["내보내기 API 추가", "CSV 생성기 구현"],
    "acceptance_criteria": ["3만 건 내보내기가 완료된다"],
    "evidence_quotes": ["비동기 사용자 CSV 내보내기 API와 생성기 구현"],
    "due": {"text": "2026-10-20", "date": "2026-10-20", "time": None},
    "assignee_names": ["박서연", "정하늘"],
    "assignees": ["seoyeon-p"],
    "unresolved_assignees": ["정하늘"],
    "expected": {"raw": "기존 이슈의 하위 티켓 (AUTH-112)", "kind": "sub_ticket", "keys": ["AUTH-112"]},
    "open_questions": [],
}


def test_body_sections_in_order():
    body = build_issue_body(TICKET, "10min_1", "배경 문단")
    order = ["## 작업 내용", "## 상세 작업", "## 완료 조건", "## 근거", "## 담당", "## 기한",
             "## 관련 이슈", "## 상위 이슈", "<summary>회의 배경</summary>", "회의: 10min_1"]
    positions = [body.index(h) for h in order]
    assert positions == sorted(positions)
    assert "- [ ] 내보내기 API 추가\n- [ ] CSV 생성기 구현" in body
    assert "## 완료 조건\n- 3만 건 내보내기가 완료된다" in body  # 완료 조건은 체크박스가 아니다
    # GitHub 계정이 없는 담당자는 이름만 적는다
    assert "- 회의록 담당자: 박서연, 정하늘" in body and "- GitHub 계정 미매핑: 정하늘" in body
    assert "@정하늘" not in body


def test_empty_sections_are_omitted():
    body = build_issue_body({"action_text": "할 일"}, "m", "")
    assert body.startswith("## 작업 내용\n할 일")
    for h in ["## 상세 작업", "## 완료 조건", "## 근거", "## 관련 이슈", "## 상위 이슈", "## 확인 필요", "회의 배경"]:
        assert h not in body
    assert "## 담당\n- 회의록 담당자: 없음" in body and "## 기한\n없음" in body  # 담당·기한은 항상 표시


def test_related_and_parent_lines():
    assert related_lines(TICKET) == (["회의록 예상 처리: 기존 이슈의 하위 티켓 (AUTH-112)"], ["AUTH-112"])
    update = {"expected": {"raw": "기존 이슈 갱신 (IAM-48)", "kind": "update", "keys": ["IAM-48"]},
              "dedupe": {"relation": "RELATED", "issue_number": 7, "issue_state": "open"}}
    assert related_lines(update) == (["회의록 예상 처리: 기존 이슈 갱신 (IAM-48)", "관련 키: IAM-48", "관련 이슈: #7"], [])
    assert related_lines({}) == ([], [])


def test_pick_labels():
    candidates = ["backend", "Frontend", "bug", "docs"]
    assert pick_labels(["Backend", "frontend", "backend", "없는 라벨"], candidates) == ["backend", "Frontend"]
    assert len(pick_labels(candidates, candidates)) == MAX_LABELS
    assert pick_labels(["backend"], []) == []


async def test_draft_uses_project_labels(settings):
    req = make_request()
    req.project.labels = ["backend", "frontend"]
    llm = FakeLLM()
    state, _ = await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), initial_state(req))
    assert llm.draft_labels[0] == ["backend", "frontend"]
    t = state["tickets"][0]
    assert t["labels"] == ["backend"]  # 후보에 없는 라벨은 빠진다
    assert t["tasks"] == [f"{t['action_text']} 작업"]


async def test_draft_falls_back_to_setting_labels(settings):
    llm = FakeLLM()
    nodes = AgentNodes(llm, FakePublisher(), FakeReporter(), replace(settings, issue_labels=["meeting"]))
    state, _ = await run_review(nodes, make_state())
    assert llm.draft_labels[0] == ["meeting"]
    assert all(t["labels"] == ["meeting"] for t in state["tickets"])


async def test_no_label_candidates(settings):
    state, _ = await run_review(AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings), make_state())
    assert all(t["labels"] == [] for t in state["tickets"])


async def test_fallback_clears_tasks(settings):
    llm = FakeLLM(review_plan={"10m1-a3": [[(4, "원문에 없는 표현")]]}, always={"10m1-a3"})
    state, _ = await run_review(AgentNodes(llm, FakePublisher(), FakeReporter(), settings), make_state())
    a3 = next(t for t in state["tickets"] if t["work_key"] == "10m1-a3")
    assert a3["fallback"] is True and a3["tasks"] == []


async def test_publish_sends_ticket_labels_and_body(settings):
    req = make_request()
    req.project.labels = ["backend"]
    pub = FakePublisher()
    state = await run_plain(AgentNodes(FakeLLM(), pub, FakeReporter(), settings), initial_state(req))
    assert pub.calls and all(c["labels"] == ["backend"] for c in pub.calls)
    assert all(c["body"].startswith("## 작업 내용") and "## 상세 작업" in c["body"] for c in pub.calls)
    content = state["result"]["items"][0]["content_json"]
    assert content["labels"] == ["backend"] and content["tasks"]
