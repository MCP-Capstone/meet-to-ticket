"""요청 1건을 처리하는 진입점. FastAPI와 CLI가 같이 쓴다."""

from __future__ import annotations

import logging
import time
import uuid
from typing import Any

import prompts
from config import Settings
from nodes import AgentNodes, run_metrics
from publisher import DryRunPublisher, McpPublisher
from reporter import BackendReporter
from schemas import RunRequest, RunResult
from state import MCP_UNAVAILABLE, STAGE_EXTRACTING, STATUS_FAILED, AgentState

log = logging.getLogger("agent.runner")


def initial_state(req: RunRequest, trace_id: str | None = None) -> AgentState:
    m = req.meeting
    return {
        "task_id": req.task_id,
        "trace_id": trace_id,
        "started_at": time.time(),
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
    state = initial_state(req, trace_id)
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
    app = build_graph(nodes)
    config = {"recursion_limit": 50, "run_name": "agent-graph", "tags": ["meet-to-ticket"], "metadata": metadata}
    final = await app.ainvoke(state, config=config)
    return RunResult(**final["result"])
