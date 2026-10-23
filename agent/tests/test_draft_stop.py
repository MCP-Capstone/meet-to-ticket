"""초안 저장에서 멈추기 (TASK-012): 발행하지 않음, 검수는 보완과 경고만, 진행 단계와 세부 정보."""

import pytest

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from graph import build_graph
from nodes import AgentNodes, new_ticket
from runner import history, run
from schemas import ExtractedTodo
from test_dedupe import FakeToolChat, ToolLLM
from test_issue_tools import make_tools

pytestmark = pytest.mark.anyio


async def run_draft(settings, llm=None, publisher=None, reporter=None, request=None, issue_tools=None):
    return await run(request or make_request(), settings, llm=llm or FakeLLM(), publisher=publisher or FakePublisher(),
                     reporter=reporter or FakeReporter(), issue_tools=issue_tools)


def by_key(result):
    return {i.work_key: i for i in result.items}


async def test_run_ends_with_drafts_without_publishing(draft_settings):
    pub, rep = FakePublisher(), FakeReporter()
    result = await run_draft(draft_settings, publisher=pub, reporter=rep)
    assert pub.calls == []  # GitHub에 아무것도 쓰지 않는다
    # 할 일 8건과 회의 결과 Slack 공지 1건 (TASK-021)
    assert result.status == "SUCCEEDED" and len(result.items) == 9
    assert all(i.review_status == "PENDING" and i.publish_status == "DRAFT" for i in result.items)
    assert result.summary["drafts"] == 9 and result.summary["published"] == 0
    # 결과 보고에도 초안 상태로 나간다
    assert {i["publishStatus"] for i in rep.results[-1]["items"]} == {"DRAFT"}
    steps = await history(result.task_id)
    assert [s["node"] for s in steps] == ["start", "prepare", "draft", "review", "dedupe", "finish"]


def test_graph_has_publish_node_only_when_publishing(draft_settings, settings):
    def nodes_of(s):
        return set(build_graph(AgentNodes(None, None, None, s)).get_graph().nodes)

    assert "publish" not in nodes_of(draft_settings)
    assert "publish" in nodes_of(settings)  # PUBLISH_ON_RUN (Phase 1 baseline)


@pytest.mark.parametrize("name,key,note", [
    ("30min_1", "30m1-a13", "회의록에 발행 보류로 표기됨 (발행 보류)"),
    ("10min_1", "10m1-a2", "조건부 처리로 표기되어 확인 필요 (조건부 기존 이슈 갱신 (IAM-48))"),
])
async def test_hold_and_conditional_become_card_warnings(draft_settings, name, key, note):
    # Phase 1에서는 확인 필요(NEEDS_CHECK)로 막혔다. 이제는 초안으로 넘기고 카드 경고로 알린다
    llm = FakeLLM(review_plan={key: [[(4, "원문에 없는 표현")]]})  # 보완으로 검수를 두 번 돌게 한다
    result = await run_draft(draft_settings, llm=llm, request=make_request(name))
    assert all(i.review_status == "PENDING" for i in result.items)
    item = by_key(result)[key]
    assert item.content_json["warnings"].count(note) == 1


async def test_review_leftover_is_handed_over_with_warning(draft_settings):
    llm = FakeLLM(review_plan={"10m1-a3": [[(4, "원문에 없는 표현")]]}, always={"10m1-a3"})
    result = await run_draft(draft_settings, llm=llm)
    a3 = by_key(result)["10m1-a3"]
    assert a3.review_status == "PENDING" and a3.publish_status == "DRAFT"
    content = a3.content_json
    # 기본 초안으로 바꾸지 않고 마지막 초안과 남은 지적을 그대로 넘긴다
    assert content["review"]["fallback"] is False and a3.title.endswith("(수정)")
    assert content["review"]["revisions"] == draft_settings.max_revisions
    assert content["review"]["notes"] == ["[4] '가나다라': 원문에 없는 표현 → 고친다"]
    assert content["warnings"].count("자동 검수 지적 1개가 남아 확인 필요") == 1


def test_unfixable_finding_is_not_rejected(draft_settings):
    nodes = AgentNodes(None, None, None, draft_settings)
    t = new_ticket(ExtractedTodo(work_key="", action=""), [], None)
    nodes._decide(t, [{"item": 1, "problem": "근거가 되는 액션 아이템이 없음", "fix": "발행하지 않음"}])
    # 보완으로 고칠 수 없으므로 바로 넘기되 반려하지 않는다
    assert t["review_status"] == "APPROVED" and t["revisions"] == 0
    assert t["warnings"] == ["자동 검수 지적 1개가 남아 확인 필요"]


async def test_progress_steps_and_details(draft_settings):
    rep = FakeReporter()
    await run_draft(draft_settings, reporter=rep)
    steps = [p["step"] for p in rep.progress_details]
    # 중복 검수는 기능이 꺼져 있으면 보이지 않는다 (기획 3.1)
    assert list(dict.fromkeys(steps)) == ["CONTEXT", "EXTRACTING", "DISPATCHING"]
    last = rep.progress_details[-1]
    assert last["context"] == {"members": 6, "issues": None, "notionDocs": None}
    assert last["extraction"] == {"todos": 8, "decisions": 6, "openQuestions": 2}
    # stage는 백엔드 TaskStage 값 그대로다 (화면 단계는 progress.step)
    assert list(dict.fromkeys(s for _, _, s in rep.progress_calls)) == ["EXTRACTING", "NORMALIZING", "REVIEWING"]


async def test_dedupe_progress_counts(draft_settings):
    rep = FakeReporter()
    chat = FakeToolChat({"비동기 사용자 CSV 내보내기 API": [
        ("search_similar_issues", {"query": "CSV 내보내기"}),  # 도구로 확인한 이슈만 가리킬 수 있다
        ("submit_decision", {"relation": "SAME", "issue_number": 12, "reason": "같은 작업"})]})
    await run_draft(draft_settings, llm=ToolLLM(chat), reporter=rep, issue_tools=make_tools()[0])
    dedupe = [p["dedupe"] for p in rep.progress_details if p["step"] == "DEDUPING"]
    assert dedupe[0] == {"done": 0, "total": 8, "linked": 0}
    assert [d["done"] for d in dedupe] == list(range(9))
    assert dedupe[-1] == {"done": 8, "total": 8, "linked": 1}  # 열린 같은 이슈 #12는 연결 대상
    assert rep.progress_details[-1]["step"] == "DISPATCHING"
    assert rep.progress_details[-1]["dedupe"] == dedupe[-1]  # 다음 단계에도 세부 정보가 남는다


async def test_job_reporter_keeps_progress_for_polling():
    pytest.importorskip("fastapi")
    import main

    inner = FakeReporter()
    reporter = main.JobReporter(inner)
    main._jobs["t-progress"] = main._running("t-progress")
    progress = {"step": "EXTRACTING", "extraction": {"todos": 3, "decisions": 1, "openQuestions": 0}}
    await reporter.progress("t-progress", "RUNNING", "NORMALIZING", progress)
    # 백엔드는 GET /runs/{taskId}를 폴링하므로 진행 세부 정보도 여기서 보인다
    assert main._jobs["t-progress"]["progress"] == progress
    assert inner.progress_details == [progress]
    del main._jobs["t-progress"]
