from __future__ import annotations

from pathlib import Path
from typing import Any

import pytest

from config import Settings
from publisher import ToolCallError
from schemas import ActionRowOut, DraftTicket, Meeting, Project, ProjectMember, ReviewCheck, RunRequest, TicketReview

SAMPLES = Path(__file__).resolve().parent.parent / "samples"

MEMBERS = [
    ProjectMember(name="김민준", github_name="minjun-k"),
    ProjectMember(name="박서연", github_name="seoyeon-p"),
    ProjectMember(name="이도윤", github_name="doyun-l"),
    ProjectMember(name="최유진", github_name="yujin-c"),
    ProjectMember(name="정하늘", github_name=None),
    ProjectMember(name="오지훈", github_name="jihoon-o"),
]


@pytest.fixture(autouse=True)
def no_tracing():
    """agent/.env에 LANGSMITH_TRACING=true가 있어도 테스트 중에는 trace를 보내지 않는다."""
    from langsmith import tracing_context

    with tracing_context(enabled=False):
        yield


@pytest.fixture(autouse=True)
def fresh_checkpointer():
    """테스트마다 같은 taskId를 쓰므로 실행 기록(checkpointer)을 테스트마다 새로 만든다."""
    import runner

    runner._checkpointer = None
    yield
    runner._checkpointer = None


@pytest.fixture
def anyio_backend():
    return "asyncio"


def sample(name: str) -> str:
    return (SAMPLES / f"{name}_summary.md").read_text(encoding="utf-8")


def make_request(name: str = "10min_1", content: str | None = None, members=None) -> RunRequest:
    return RunRequest(
        task_id=f"task-{name}",
        meeting=Meeting(meeting_id=name, title="t", original_content=content if content is not None else sample(name)),
        project=Project(github_owner="MCP-Capstone", github_repo="mcp-capstone"),
        project_members=MEMBERS if members is None else members,
    )


def make_state(name: str = "10min_1", content: str | None = None, members=None) -> dict[str, Any]:
    """백엔드 요청 1건을 그래프 초기 상태로 바꾼다 (테스트용)."""
    from runner import initial_state

    return initial_state(make_request(name, content, members))


class FakeLLM:
    """초안은 액션 문구로 만들고, 검수는 미리 정한 실패를 돌려준다.

    review_plan: {work_key: [[실패 목록 1회차], [실패 목록 2회차], ...]}
    실패 항목은 (item, problem) 튜플.
    """

    def __init__(self, review_plan: dict[str, list[list[tuple[int, str]]]] | None = None,
                 fail_on: str | None = None, rows: list[ActionRowOut] | None = None,
                 bad_quote_for: set[str] | None = None, always: set[str] | None = None):
        self.always = always or set()
        self.review_plan = review_plan or {}
        self.review_round: dict[str, int] = {}
        self.fail_on = fail_on
        self.rows = rows or []
        self.bad_quote_for = bad_quote_for or set()
        self.draft_calls: list[list[dict[str, Any]]] = []
        self.review_contexts: list[Any] = []

    async def extract_rows(self, summary: str) -> list[ActionRowOut]:
        if self.fail_on == "rows":
            from state import AgentFatal
            raise AgentFatal("LLM_ERROR", "boom")
        return self.rows

    async def draft(self, summary: str, items: list[dict[str, Any]]) -> list[DraftTicket]:
        if self.fail_on == "draft":
            from state import AgentFatal
            raise AgentFatal("LLM_ERROR", "boom")
        self.draft_calls.append(items)
        out = []
        for it in items:
            quote = it["action"]
            if it["work_key"] in self.bad_quote_for and "feedback" not in it:
                quote = "원문에 없는 문장"
            out.append(DraftTicket(
                work_key=it["work_key"],
                title=f"{it['action']}" + (" (수정)" if "feedback" in it else ""),
                description=f"{it['action']}을(를) 진행한다. 가나다라 표시.",
                acceptance_criteria=["결과를 팀에 공유한다"],
                evidence_quotes=[quote],
                open_questions=[],
            ))
        return out

    async def review(self, summary: str, drafts: list[dict[str, Any]], context=None) -> list[TicketReview]:
        self.review_contexts.append(context)
        if self.fail_on == "review":
            from state import AgentFatal
            raise AgentFatal("LLM_ERROR", "boom")
        out = []
        for d in drafts:
            key = d["work_key"]
            n = self.review_round.get(key, 0)
            self.review_round[key] = n + 1
            plan = self.review_plan.get(key, [])
            if n < len(plan):
                fails = plan[n]
            elif plan and key in self.always:
                fails = plan[-1]
            else:
                fails = []
            failed = {i: p for i, p in fails}
            checks = []
            for item in (2, 3, 4, 7, 8):
                if item in failed:
                    checks.append(ReviewCheck(item=item, passed=False, quote="가나다라", problem=failed[item], fix="고친다"))
                else:
                    checks.append(ReviewCheck(item=item, passed=True, quote="", problem="", fix=""))
            out.append(TicketReview(work_key=key, checks=checks))
        return out


class FakePublisher:
    def __init__(self, fail: dict[str, ToolCallError] | None = None):
        self.fail = fail or {}
        self.calls: list[dict[str, Any]] = []
        self.next_number = 100

    async def create_issue(self, **args: Any) -> dict[str, Any]:
        self.calls.append(args)
        for needle, err in self.fail.items():
            if needle in args["title"]:
                raise err
        self.next_number += 1
        return {"status": "created", "number": self.next_number,
                "url": f"https://github.com/o/r/issues/{self.next_number}",
                "assignees": args.get("assignees", []), "dropped_assignees": []}


class FakeReporter:
    def __init__(self):
        self.progress_calls: list[tuple[str, str, str]] = []
        self.results: list[dict[str, Any]] = []

    async def progress(self, task_id: str, status: str, stage: str) -> None:
        self.progress_calls.append((task_id, status, stage))

    async def result(self, task_id: str, payload: dict[str, Any]) -> bool:
        self.results.append(payload)
        return True


@pytest.fixture
def settings() -> Settings:
    return Settings(max_revisions=2)


async def run_review(nodes, state, max_steps: int = 50):
    """LangGraph 없이 prepare → draft ⇄ review를 그래프와 같은 순서로 실행한다 (테스트용).

    발행 직전 상태와 다음 단계 이름(publish 또는 finish)을 돌려준다.
    """
    from nodes import route_after_draft, route_after_prepare, route_after_review

    current = dict(state)
    step = "prepare"
    routes = {"prepare": route_after_prepare, "draft": route_after_draft, "review": route_after_review}
    for _ in range(max_steps):
        current.update(await getattr(nodes, step)(current))
        step = routes[step](current)
        if step not in routes:
            return current, step
    raise RuntimeError("최대 단계 수 초과")


async def run_plain(nodes, state, max_steps: int = 50):
    """LangGraph 없이 그래프와 같은 순서로 노드를 실행한다 (테스트용)."""
    from nodes import route_after_draft, route_after_prepare, route_after_review

    current = dict(state)
    step = "prepare"
    routes = {"prepare": route_after_prepare, "draft": route_after_draft, "review": route_after_review}
    for _ in range(max_steps):
        current.update(await getattr(nodes, step)(current))
        if step == "finish":
            return current
        step = routes[step](current) if step in routes else "finish"
    raise RuntimeError("최대 단계 수 초과")
