"""요청 1건을 처리하는 진입점. FastAPI와 CLI가 같이 쓴다."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections import Counter
from typing import Any

import prompts
from config import Settings
from issue_tools import IssueTools
from nodes import AgentNodes, run_metrics
from project_context import ProjectContextTools
from publisher import DryRunPublisher, McpPublisher
from reporter import BackendReporter
from schemas import PublishBatchRequest, PublishBatchResult, RunRequest, RunResult
from state import MCP_UNAVAILABLE, STAGE_EXTRACTING, STATUS_FAILED, STATUS_RUNNING, STEP_CONTEXT, AgentState

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

    # 상태 조회만 하고 실행하지 않는다. 발행 노드가 있는 그래프로 읽어야 PUBLISH_ON_RUN 실행 기록도 노드 이름이 맞다
    app = build_graph(AgentNodes(None, None, None, Settings(publish_on_run=True)), checkpointer())
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
        "meeting_title": m.title or "",
        "meeting_date": m.meeting_date.isoformat() if m.meeting_date else None,
        "original_content": m.original_content,
        "owner": req.project.github_owner,
        "repo": req.project.github_repo,
        "notion_page_url": req.project.notion_page_url,
        "members": [pm.model_dump() for pm in req.project_members],
        "tickets": [],
        "stage": STAGE_EXTRACTING,
        "error_code": None,
        "error_message": None,
    }


def _mcp_client(name: str, url: str) -> Any:
    from langchain_mcp_adapters.client import MultiServerMCPClient

    return MultiServerMCPClient({name: {"transport": "streamable_http", "url": url}})


async def github_tools(settings: Settings) -> dict[str, Any]:
    """business-mcp의 도구 (create_issue, search_issues 등). 연결이 안 되면 예외."""
    tools = await _mcp_client("github", settings.mcp_server_url).get_tools(server_name="github")
    return {t.name: t for t in tools}


def make_mcp_publisher(tools: dict[str, Any]) -> McpPublisher:
    if "create_issue" not in tools:
        raise RuntimeError(f"create_issue 도구를 찾지 못함: {sorted(tools)}")
    return McpPublisher(tools["create_issue"], tools.get("add_comment"), tools.get("update_issue"), tools.get("post_message"),
                        tools.get("search_issues"), tools.get("find_marker"), tools.get("create_page"))


async def make_issue_tools(
    settings: Settings, owner: str, repo: str, github: dict[str, Any] | None
) -> tuple[IssueTools | None, str]:
    """중복 판단용 도구를 준비한다. (도구, 상태: disabled | unavailable | ready)

    rag-mcp는 github 서버와 따로 연결한다. rag-mcp가 꺼져 있어도 발행은 그대로 해야 하기 때문이다.
    """
    if not settings.rag_enabled:
        return None, "disabled"
    client = _mcp_client("rag", settings.rag_mcp_url)
    try:
        rag = {t.name: t for t in await client.get_tools(server_name="rag")}
    except Exception as exc:  # noqa: BLE001
        log.warning("rag-mcp 연결 실패 (%s), 중복 확인 없이 진행: %s", settings.rag_mcp_url, exc)
        return None, "unavailable"
    if "search_similar_issues" not in rag:
        log.warning("rag-mcp에 search_similar_issues가 없어 중복 확인 없이 진행: %s", sorted(rag))
        return None, "unavailable"

    async def read_resource(uri: str) -> str:
        blobs = await client.get_resources("rag", uris=[uri])
        return "\n".join(b.as_string() for b in blobs)

    tools = IssueTools(
        owner, repo,
        similar=rag["search_similar_issues"],
        keyword=(github or {}).get("search_issues"),
        reader=read_resource,
        sync_tool=rag.get("sync_issues"),
        index_tool=rag.get("index_issue"),
        status_tool=rag.get("index_status"),
        max_chars=settings.tool_result_max_chars,
    )
    return tools, "ready"


async def report_context_started(req: RunRequest, reporter: Any) -> None:
    progress = {"step": STEP_CONTEXT,
                "context": {"members": len(req.project_members), "issues": None, "notionDocs": None}}
    try:
        await reporter.progress(req.task_id, STATUS_RUNNING, STAGE_EXTRACTING, progress)
    except Exception as exc:  # noqa: BLE001 - 보고 실패로 작업을 멈추지 않는다
        log.warning("진행 보고 실패: %s", exc)


async def sync_index(tools: IssueTools, settings: Settings) -> dict[str, Any] | None:
    """실행마다 1회 색인을 최신 상태로 맞춘다 (바뀐 이슈만). 시간 안에 끝나지 않으면 건너뛴다.

    분석 직전에 만든 이슈가 GitHub 목록에 늦게 보여 중복 판단을 놓치지 않도록, 변경 없음이면 한 번 더 확인한다.
    발행 전 재대조(recheck)는 대기 비용을 피하려고 이 확인을 쓰지 않는다.
    """
    try:
        return await asyncio.wait_for(tools.sync(recheck_empty=True), timeout=settings.rag_sync_timeout_seconds)
    except asyncio.TimeoutError:
        log.warning("색인 갱신이 %s초 안에 끝나지 않아 건너뜀", settings.rag_sync_timeout_seconds)
        return None


async def run_publish_batch(
    request: PublishBatchRequest, settings: Settings, *, publisher: Any | None = None, llm: Any | None = None,
) -> PublishBatchResult:
    """확인된 여러 프로젝트 티켓을 발행한다.

    프로젝트별로 재대조가 색인을 한 번 증분 동기화한다. 재대조·색인 실패는 기존 처리 유형과 GitHub 발행을 막지 않는다.
    """
    from batch_publish import _ticket, publish_batch
    from recheck import recheck

    github: dict[str, Any] | None = None
    if publisher is None:
        if settings.dry_run:
            publisher = DryRunPublisher()
        else:
            github = await github_tools(settings)
            publisher = make_mcp_publisher(github)
    needs_recheck = any(t.publish_target == "GITHUB" and not t.retry_comment_only for t in request.tickets)
    if settings.rag_enabled and needs_recheck and llm is None:
        from llm import OpenAILLM
        llm = OpenAILLM(settings)

    # 재대조는 같은 저장소 묶음마다 sync_issues 한 번을 수행한다.
    # GitHub 티켓만 대조한다. Slack·Notion은 기존 GitHub 이슈와 연결할 수 없고 항상 신규 발행이다 (#297)
    prepared = list(request.tickets)
    by_repo: dict[tuple[str, str], list[int]] = {}
    for index, ticket in enumerate(prepared):
        if ticket.publish_target != "GITHUB" or ticket.retry_comment_only:
            continue
        by_repo.setdefault((ticket.project.github_owner, ticket.project.github_repo), []).append(index)
    indexed: dict[tuple[str, str], IssueTools] = {}
    if settings.rag_enabled:
        for key, positions in by_repo.items():
            tools, _ = await make_issue_tools(settings, key[0], key[1], github)
            if tools is None or llm is None:
                continue
            checked = await recheck([_ticket(prepared[pos]) for pos in positions], issue_tools=tools, llm=llm, settings=settings)
            for pos, value in zip(positions, checked["tickets"]):
                handling = value.get("handling") or {}
                prepared[pos] = prepared[pos].model_copy(update={
                    "processing_type": handling.get("type", prepared[pos].processing_type),
                    "target_issue_number": handling.get("issue_number", prepared[pos].target_issue_number),
                })
            indexed[key] = tools

    result = await publish_batch(request.model_copy(update={"tickets": prepared}), publisher)
    # 발행/댓글/갱신 직후 최신 본문·댓글을 다시 색인한다. 실패는 결과 성공을 바꾸지 않는다.
    source = {ticket.ticket_id: ticket for ticket in prepared}
    for item in result.items:
        ticket = source[item.ticket_id]
        tools = indexed.get((ticket.project.github_owner, ticket.project.github_repo))
        if tools and item.github_issue_number and item.status in {"PUBLISHED", "LINKED", "UPDATED"}:
            await tools.index(item.github_issue_number)
    return result


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
    issue_tools: IssueTools | None = None,
    notion_tools: dict[str, Any] | None = None,
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
        result = await _run(req, settings, trace_id, metadata, llm=llm, publisher=publisher, reporter=reporter,
                            issue_tools=issue_tools, notion_tools=notion_tools)
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
    issue_tools: IssueTools | None = None,
    notion_tools: dict[str, Any] | None = None,
) -> RunResult:
    state = initial_state(req, trace_id, settings.task_deadline_seconds)
    publisher_was_injected = publisher is not None  # 테스트에서 가짜 발행 도구를 넣은 경우
    reporter = reporter or BackendReporter(settings.backend_base_url, settings.backend_token)
    if llm is None:
        from llm import OpenAILLM

        llm = OpenAILLM(settings)
    github: dict[str, Any] | None = None
    if publisher is None:
        if settings.dry_run:
            publisher = DryRunPublisher()
        else:
            try:
                github = await github_tools(settings)
                publisher = make_mcp_publisher(github)
            except Exception as exc:  # noqa: BLE001
                log.error("MCP 서버 연결 실패 task=%s: %s", req.task_id, exc)
                result = RunResult(
                    **_failed(req, MCP_UNAVAILABLE, f"MCP 서버 연결 실패 ({settings.mcp_server_url}): {exc}"),
                    trace_id=trace_id,
                    metrics=run_metrics(settings, llm, state["started_at"]),
                )
                await reporter.result(req.task_id, result.model_dump(by_alias=True, mode="json"))
                return result

    if issue_tools is None:
        if settings.rag_enabled and github is None and not publisher_was_injected:
            # dry run이어도 조회 도구(search_issues)는 쓸 수 있게 github 서버에 붙어 본다
            try:
                github = await github_tools(settings)
            except Exception as exc:  # noqa: BLE001
                log.warning("business-mcp 조회 도구를 쓰지 못함 (키워드 검색 없이 진행): %s", exc)
        issue_tools, rag_status = await make_issue_tools(
            settings, req.project.github_owner, req.project.github_repo, github)
    else:
        rag_status = "ready"
    # 색인 갱신과 맥락 불러오기는 오래 걸릴 수 있어(각 최대 30초) 진행 화면에 "맥락 파악"을 먼저 보고한다.
    # 불러온 개수는 prepare가 다시 보고한다
    await report_context_started(req, reporter)
    state["rag"] = {"status": rag_status, "sync": await sync_index(issue_tools, settings) if issue_tools else None}

    if notion_tools is None and req.project.notion_page_url and not publisher_was_injected:
        # Notion 도구(list_pages, read_page)도 business-mcp에 있다. dry run이면 아직 연결하지 않았을 수 있다
        if github is None:
            try:
                github = await github_tools(settings)
            except Exception as exc:  # noqa: BLE001
                log.warning("business-mcp에 연결하지 못해 Notion 맥락 없이 진행: %s", exc)
        notion_tools = github
    context_source = notion_tools or github or {}
    context_tools = None
    if context_source and (context_source.get("list_issues") is not None or req.project.notion_page_url is not None):
        context_tools = ProjectContextTools(
            context_source.get("list_issues"), context_source.get("list_pages"), context_source.get("read_page"),
            max_chars=settings.tool_result_max_chars,
        )

    from graph import build_graph

    nodes = AgentNodes(
        llm=llm, publisher=publisher, reporter=reporter, settings=settings, issue_tools=issue_tools,
        context_tools=context_tools,
    )
    app = build_graph(nodes, checkpointer())
    config = {"recursion_limit": 50, "run_name": "agent-graph", "tags": ["meet-to-ticket"], "metadata": metadata,
              **_thread(req.task_id)}
    final = await app.ainvoke(state, config=config)
    return RunResult(**final["result"])
