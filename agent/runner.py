"""요청 1건을 처리하는 진입점. FastAPI와 CLI가 같이 쓴다."""

from __future__ import annotations

import logging
from typing import Any

from config import Settings
from nodes import AgentNodes
from publisher import DryRunPublisher, McpPublisher
from reporter import BackendReporter
from schemas import RunRequest, RunResult
from state import MCP_UNAVAILABLE, STAGE_EXTRACTING, STATUS_FAILED, AgentState

log = logging.getLogger("agent.runner")


def initial_state(req: RunRequest) -> AgentState:
    m = req.meeting
    return {
        "task_id": req.task_id,
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
                log.error("MCP 서버 연결 실패: %s", exc)
                result = RunResult(**_failed(req, MCP_UNAVAILABLE, f"MCP 서버 연결 실패 ({settings.mcp_server_url}): {exc}"))
                await reporter.result(req.task_id, result.model_dump(by_alias=True, mode="json"))
                return result

    from graph import build_graph

    nodes = AgentNodes(llm=llm, publisher=publisher, reporter=reporter, settings=settings)
    app = build_graph(nodes)
    final = await app.ainvoke(initial_state(req), config={"recursion_limit": 50})
    return RunResult(**final["result"])
