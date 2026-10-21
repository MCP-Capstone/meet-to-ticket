"""요청 1건을 처리하는 진입점. FastAPI와 CLI가 같이 쓴다."""

from __future__ import annotations

import logging
import time
import uuid
from collections import Counter
from typing import Any

import prompts
from config import Settings
from nodes import AgentNodes, run_metrics
from publisher import DryRunPublisher, McpPublisher
from reporter import BackendReporter
from schemas import RunRequest, RunResult
from state import MCP_UNAVAILABLE, STAGE_EXTRACTING, STATUS_FAILED, AgentState

log = logging.getLogger("agent.runner")

# 이슈가 이미 만들어졌거나 만들어졌을 수 있는 발행 상태
PUBLISHED_STATUSES = {"SUCCEEDED", "UNKNOWN"}

_checkpointer: Any | None = None


def checkpointer() -> Any:
    """프로세스 공용 checkpointer. 메모리에 저장하므로 프로세스를 재시작하면 사라진다."""
    global _checkpointer
    if _checkpointer is None:
        from langgraph.checkpoint.memory import InMemorySaver  # 테스트 환경에서 import하지 않도록 지연 import

        _checkpointer = InMemorySaver()
    return _checkpointer


def _thread(task_id: str) -> dict[str, Any]:
    return {"configurable": {"thread_id": task_id}}


async def stored_result(task_id: str) -> RunResult | None:
    """이 프로세스에서 이미 끝낸 작업이면 그 결과를 돌려준다 (다시 실행하지 않음).

    발행됐거나 발행 여부가 불명인 이슈가 있는 작업만 해당한다. 같은 taskId를 다시 실행하면 이슈가
    중복으로 만들어지기 때문이다. 발행 전에 끝난 작업(LLM 오류 등)은 기록을 지우고 None을 돌려줘
    같은 taskId로 다시 실행할 수 있게 한다.
    """
    cp = checkpointer()
    saved = await cp.aget_tuple(_thread(task_id))
    if saved is None:
        return None
    result = saved.checkpoint["channel_values"].get("result")
    if result and any(i["publish_status"] in PUBLISHED_STATUSES for i in result["items"]):
        return RunResult(**result)
    await cp.adelete_thread(task_id)
    return None


async def history(task_id: str) -> list[dict[str, Any]] | None:
    """노드가 끝날 때마다 저장된 상태를 시간 순서로 요약한다. 기록이 없으면 None."""
    from graph import build_graph

    app = build_graph(AgentNodes(None, None, None, Settings()), checkpointer())  # 상태 조회만 하고 실행하지 않음
    snapshots = [s async for s in app.aget_state_history(_thread(task_id))]
    if not snapshots:
        return None
    steps: list[dict[str, Any]] = []
    ran: tuple[str, ...] = ()
    for snap in reversed(snapshots):
        if snap.metadata.get("source") == "input":
            ran = snap.next
            continue
        tickets = snap.values.get("tickets", [])
        steps.append({
            "step": snap.metadata.get("step"),
            "node": ", ".join(n for n in ran if n != "__start__") or "start",  # 이 상태를 만든 노드
            "next": list(snap.next),
            "stage": snap.values.get("stage"),
            "tickets": len(tickets),
            "reviewStatus": dict(Counter(t["review_status"] for t in tickets)),
            "published": sum(1 for t in tickets if t["publish_status"] == "SUCCEEDED"),
            "errorCode": snap.values.get("error_code"),
            "createdAt": snap.created_at,
        })
        ran = snap.next
    return steps


def initial_state(req: RunRequest, trace_id: str | None = None, deadline_seconds: int = 0) -> AgentState:
    m = req.meeting
    started_at = time.time()
    return {
        "task_id": req.task_id,
        "trace_id": trace_id,
        "started_at": started_at,
        "deadline_at": started_at + deadline_seconds if deadline_seconds > 0 else None,
        "meeting_id": str(m.meeting_id),
        "meeting_date": m.meeting_date.isoformat() if m.meeting_date else None,
        "original_content": m.original_content,
        "owner": req.project.github_owner,
        "repo": req.project.github_repo,
        "members": [pm.model_dump() for pm in req.project_members],
        "tickets": [],
        "stage": STAGE_EXTRACTING,
        "error_code": None,
        "error_message": None,
    }


async def make_mcp_publisher(settings: Settings) -> McpPublisher:
    from langchain_mcp_adapters.client import MultiServerMCPClient

    client = MultiServerMCPClient(
        {"github": {"transport": "streamable_http", "url": settings.mcp_server_url}}
    )
    tools = await client.get_tools()
    tool = next((t for t in tools if t.name == "create_issue"), None)
    if tool is None:
        raise RuntimeError(f"create_issue 도구를 찾지 못함: {[t.name for t in tools]}")
    return McpPublisher(tool)


def _failed(req: RunRequest, code: str, message: str) -> dict[str, Any]:
    return {
        "task_id": req.task_id,
        "status": STATUS_FAILED,
        "stage": STAGE_EXTRACTING,
        "error_code": code,
        "error_message": message,
        "summary": {},
        "items": [],
    }


async def run(
    req: RunRequest,
    settings: Settings,
    *,
    llm: Any | None = None,
    publisher: Any | None = None,
    reporter: Any | None = None,
) -> RunResult:
    """요청 1건을 하나의 trace로 실행한다.

    LANGSMITH_TRACING=true면 그래프 노드와 LLM 호출(Token 포함)이 이 trace 아래에 모인다.
    꺼져 있어도 run id는 만들어 결과의 traceId로 돌려준다.
    """
    from langsmith import trace

    previous = await stored_result(req.task_id)
    if previous is not None:
        log.info("이미 발행까지 끝난 작업이라 다시 실행하지 않음 task=%s trace=%s", req.task_id, previous.trace_id)
        return previous

    trace_id = str(uuid.uuid4())
    meeting_id = str(req.meeting.meeting_id)
    metadata = {
        "task_id": req.task_id,
        "meeting_id": meeting_id,
        "prompt_version": prompts.PROMPT_VERSION,
        "model": settings.openai_model,
        "review_model": settings.review_model,
    }
    log.info("작업 시작 task=%s trace=%s meeting=%s", req.task_id, trace_id, meeting_id)
    async with trace(
        "meet-to-ticket",
        run_type="chain",
        run_id=trace_id,
        inputs={"taskId": req.task_id, "meetingId": meeting_id,
                "repo": f"{req.project.github_owner}/{req.project.github_repo}"},
        metadata=metadata,
        tags=["meet-to-ticket"],
    ) as run_tree:
        result = await _run(req, settings, trace_id, metadata, llm=llm, publisher=publisher, reporter=reporter)
        run_tree.end(outputs={"status": result.status, "errorCode": result.error_code, "summary": result.summary})
    log.info("작업 끝 task=%s trace=%s status=%s error=%s %sms", req.task_id, trace_id,
             result.status, result.error_code, result.metrics.get("durationMs"))
    return result


async def _run(
    req: RunRequest,
    settings: Settings,
    trace_id: str,
    metadata: dict[str, Any],
    *,
    llm: Any | None,
    publisher: Any | None,
    reporter: Any | None,
) -> RunResult:
    state = initial_state(req, trace_id, settings.task_deadline_seconds)
    reporter = reporter or BackendReporter(settings.backend_base_url, settings.backend_token)
    if llm is None:
        from llm import OpenAILLM

        llm = OpenAILLM(settings)
    if publisher is None:
        if settings.dry_run:
            publisher = DryRunPublisher()
        else:
            try:
                publisher = await make_mcp_publisher(settings)
            except Exception as exc:  # noqa: BLE001
                log.error("MCP 서버 연결 실패 task=%s: %s", req.task_id, exc)
                result = RunResult(
                    **_failed(req, MCP_UNAVAILABLE, f"MCP 서버 연결 실패 ({settings.mcp_server_url}): {exc}"),
                    trace_id=trace_id,
                    metrics=run_metrics(settings, llm, state["started_at"]),
                )
                await reporter.result(req.task_id, result.model_dump(by_alias=True, mode="json"))
                return result

    from graph import build_graph

    nodes = AgentNodes(llm=llm, publisher=publisher, reporter=reporter, settings=settings)
    app = build_graph(nodes, checkpointer())
    config = {"recursion_limit": 50, "run_name": "agent-graph", "tags": ["meet-to-ticket"], "metadata": metadata,
              **_thread(req.task_id)}
    final = await app.ainvoke(state, config=config)
    return RunResult(**final["result"])
