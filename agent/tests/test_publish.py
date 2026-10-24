import pytest

from conftest import PUBLISHED_10MIN, FakeLLM, FakePublisher, FakeReporter, make_state, run_plain
from nodes import AgentNodes
from publisher import DryRunPublisher, ToolCallError

pytestmark = pytest.mark.anyio


async def run_case(settings, state=None, llm=None, publisher=None, reporter=None):
    nodes = AgentNodes(llm or FakeLLM(), publisher or FakePublisher(), reporter or FakeReporter(), settings)
    final = await run_plain(nodes, state or make_state())
    return final["result"]


def by_key(result):
    return {i["work_key"]: i for i in result["items"]}


async def test_10min_publish_and_report(settings):
    pub, rep = FakePublisher(), FakeReporter()
    result = await run_case(settings, publisher=pub, reporter=rep)
    items = by_key(result)
    assert result["status"] == "SUCCEEDED"
    assert len(items) == 8
    # 조건부 기존 이슈 갱신(a2, a8)과 담당자가 여러 명인 a7은 발행하지 않고, 담당자 한 명인 나머지만 발행
    for key in ("10m1-a2", "10m1-a7", "10m1-a8"):
        assert items[key]["publish_status"] == "BLOCKED"
    published = [i for i in result["items"] if i["publish_status"] == "SUCCEEDED"]
    assert {i["work_key"] for i in published} == set(PUBLISHED_10MIN) and len(pub.calls) == len(PUBLISHED_10MIN)
    assert all(i["action"] == "CREATE" and i["github_issue_url"] for i in published)
    # 다중 담당자: 전원 멤버와 매핑되지만 한 명으로 정해지지 않아 막힌다 (#292). 기한 시각은 그대로 해석한다
    a7 = items["10m1-a7"]["content_json"]
    assert a7["assignees"]["github"] == ["minjun-k", "seoyeon-p", "doyun-l", "yujin-c", "haneul-j", "jihoon-o"]
    assert a7["due"]["date"] == "2026-10-22" and a7["due"]["time"] == "14:00"
    call = next(c for c in pub.calls if "CSV 내보내기 API" in c["title"])
    assert call["assignees"] == ["seoyeon-p"]
    assert "회의: 10min_1 · work_key: 10m1-a1" in call["body"]
    assert "2026-10-20" in call["body"]
    # 진행 보고와 결과 보고
    stages = [s for _, _, s in rep.progress_calls]
    assert stages[0] == "EXTRACTING" and "REVIEWING" in stages and stages[-1] == "PUBLISHING"
    assert rep.results and rep.results[-1]["status"] == "SUCCEEDED"
    assert "taskId" in rep.results[-1]
    assert result["summary"]["published"] == len(PUBLISHED_10MIN) and result["summary"]["needsCheck"] == 3


async def test_conditional_published_when_setting_off(settings):
    settings.conditional_as_needs_check = False
    result = await run_case(settings)
    assert by_key(result)["10m1-a2"]["publish_status"] == "SUCCEEDED"


async def test_fallback_draft_is_published_with_review_note(settings):
    llm = FakeLLM(review_plan={"10m1-a3": [[(4, "원문에 없는 표현")]]}, always={"10m1-a3"})
    pub = FakePublisher()
    result = await run_case(settings, llm=llm, publisher=pub)
    a3 = by_key(result)["10m1-a3"]
    assert a3["publish_status"] == "SUCCEEDED"
    assert a3["content_json"]["review"]["fallback"] is True
    body = next(c["body"] for c in pub.calls if c["title"] == a3["title"])
    assert "## 검수 메모" in body


async def test_revise_limit_needs_check_is_blocked(settings):
    settings.fallback_publish = False
    llm = FakeLLM(review_plan={"10m1-a3": [[(4, "원문에 없는 표현")]]}, always={"10m1-a3"})
    result = await run_case(settings, llm=llm)
    assert by_key(result)["10m1-a3"]["publish_status"] == "BLOCKED"


async def test_partial_publish_failure(settings):
    pub = FakePublisher(fail={"QA 사용자": ToolCallError("GITHUB_ERROR", "server error")})
    result = await run_case(settings, publisher=pub)
    a6 = by_key(result)["10m1-a6"]
    assert a6["publish_status"] == "FAILED"
    assert a6["error_message"].startswith("[GITHUB_ERROR]")
    assert result["status"] == "SUCCEEDED"
    assert result["summary"]["published"] == len(PUBLISHED_10MIN) - 1


async def test_unknown_publish_result(settings):
    pub = FakePublisher(fail={"QA 사용자": ToolCallError("PUBLISH_FAILED", "timeout", ambiguous=True)})
    result = await run_case(settings, publisher=pub)
    assert by_key(result)["10m1-a6"]["publish_status"] == "UNKNOWN"
    assert result["summary"]["publishUnknown"] == 1


@pytest.mark.parametrize("code", ["GITHUB_AUTH", "REPOSITORY_NOT_ALLOWED"])
async def test_fatal_error_stops_remaining(settings, code):
    pub = FakePublisher(fail={"CSV 내보내기 API": ToolCallError(code, "stop")})
    result = await run_case(settings, publisher=pub)
    assert result["status"] == "FAILED"
    assert result["error_code"] == code
    assert len(pub.calls) == 1
    # 처음 실패한 a1과, 발행 대상이었지만 쓰지 못한 나머지가 모두 FAILED다 (막힌 티켓은 BLOCKED 그대로)
    failed = [i for i in result["items"] if i["publish_status"] == "FAILED"]
    assert {i["work_key"] for i in failed} == set(PUBLISHED_10MIN)


async def test_dropped_assignees_warning(settings):
    class DroppingPublisher(FakePublisher):
        async def create_issue(self, **args):
            res = await super().create_issue(**args)
            res["dropped_assignees"] = args["assignees"]
            return res

    result = await run_case(settings, publisher=DroppingPublisher())
    a1 = by_key(result)["10m1-a1"]
    assert a1["publish_status"] == "SUCCEEDED"
    assert "GitHub에서 담당자 지정이 안 됨: seoyeon-p" in a1["content_json"]["warnings"]


async def test_dropped_labels_warning(settings):
    class DroppingPublisher(FakePublisher):
        async def create_issue(self, **args):
            res = await super().create_issue(**args)
            res["dropped_labels"] = ["deleted-label", "old-label"]
            return res

    result = await run_case(settings, publisher=DroppingPublisher())
    a1 = by_key(result)["10m1-a1"]
    assert a1["publish_status"] == "SUCCEEDED"
    assert "저장소에 없는 라벨이라 빠짐: deleted-label, old-label" in a1["content_json"]["warnings"]


@pytest.mark.parametrize("where", ["draft", "review"])
async def test_llm_error_fails_task(settings, where):
    pub = FakePublisher()
    result = await run_case(settings, llm=FakeLLM(fail_on=where), publisher=pub)
    assert result["status"] == "FAILED"
    assert result["error_code"] == "LLM_ERROR"
    assert pub.calls == []


async def test_no_tickets(settings):
    content = "# 회의 요약\n\n- 회의 ID: y\n\n## 논의\n\n잡담만 했다.\n"
    result = await run_case(settings, state=make_state("y", content), llm=FakeLLM(rows=[]))
    assert result["status"] == "SUCCEEDED"
    assert result["error_code"] == "NO_TICKETS"
    assert result["items"] == []


async def test_30min_publishes_all_but_hold(settings):
    pub = FakePublisher()
    result = await run_case(settings, state=make_state("30min_1"), publisher=pub)
    assert by_key(result)["30m1-a13"]["publish_status"] == "BLOCKED"
    assert len(pub.calls) == 12
    body = next(c["body"] for c in pub.calls if "AUTH-112 재개" in c["title"])
    assert "관련 키: AUTH-112" in body


async def test_60min_publishes_all_24(settings):
    pub = FakePublisher()
    result = await run_case(settings, state=make_state("60min_1"), publisher=pub)
    assert len(result["items"]) == 24
    assert result["summary"]["published"] == 24


async def test_dry_run(settings):
    pub = DryRunPublisher()
    result = await run_case(settings, publisher=pub)
    approved = [i for i in result["items"] if i["review_status"] == "APPROVED"]
    # 백엔드 Live 검증: SKIPPED는 이슈 번호·URL이 없어야 한다
    assert approved and all(i["publish_status"] == "SKIPPED" for i in approved)
    assert all(i["github_issue_number"] is None and i["github_issue_url"] is None for i in approved)
    assert len(pub.calls) == len(PUBLISHED_10MIN)
