"""프로젝트별 GitHub 이슈 RAG 색인 관리 API.

이 모듈은 ``main.py``가 ``create_router(_settings, lambda: app.state.overrides)``로
등록해서 사용한다. 분석 실행과 달리 LLM을 사용하지 않으며, RAG MCP의
``sync_issues(full=true)``만 비동기로 실행한다.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import Callable
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from config import Settings
from publisher import error_from_exception, parse_tool_result
from state import MCP_UNAVAILABLE

log = logging.getLogger("agent.reindex")


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ReindexRequest(CamelModel):
    github_owner: str
    github_repo: str


def create_router(
    settings_provider: Callable[[], Settings],
    overrides_provider: Callable[[], dict[str, Any]] | None = None,
) -> APIRouter:
    """독립 라우터를 만든다.

    main 모듈의 전역 상태에 의존하지 않게 해서 FastAPI 단위 테스트와 다른 실행
    진입점에서도 같은 색인 작업을 쓸 수 있다. ``rag_tools`` override는 테스트용이다.
    """
    router = APIRouter(prefix="/projects", tags=["프로젝트"])
    jobs: dict[str, dict[str, Any]] = {}
    active_repositories: set[tuple[str, str]] = set()
    tasks: set[asyncio.Task[None]] = set()

    def overrides() -> dict[str, Any]:
        return overrides_provider() if overrides_provider is not None else {}

    async def rag_tools(settings: Settings) -> dict[str, Any]:
        supplied = overrides().get("rag_tools")
        if supplied is not None:
            return supplied
        try:
            from runner import _mcp_client

            client = _mcp_client("rag", settings.rag_mcp_url)
            return {tool.name: tool for tool in await client.get_tools(server_name="rag")}
        except Exception as exc:  # noqa: BLE001
            raise error_from_exception(exc, MCP_UNAVAILABLE) from exc

    async def invoke(tool: Any, args: dict[str, Any]) -> dict[str, Any]:
        try:
            return parse_tool_result(await tool.ainvoke(args))
        except Exception as exc:  # noqa: BLE001
            raise error_from_exception(exc, MCP_UNAVAILABLE) from exc

    async def execute(job_id: str, owner: str, repo: str) -> None:
        job = jobs[job_id]
        key = (owner.lower(), repo.lower())
        try:
            settings = settings_provider()
            if not settings.rag_enabled:
                raise RuntimeError("[RAG_DISABLED] RAG 색인이 비활성화되어 있습니다")
            toolset = await rag_tools(settings)
            sync = toolset.get("sync_issues")
            status = toolset.get("index_status")
            if sync is None or status is None:
                raise RuntimeError("[MCP_UNAVAILABLE] RAG MCP에 sync_issues 또는 index_status 도구가 없습니다")

            job["progress"] = {"stage": "SYNCING", "message": "GitHub 이슈 전체를 다시 색인하고 있습니다."}
            synced = await invoke(sync, {"owner": owner, "repo": repo, "full": True})
            job["progress"] = {"stage": "VERIFYING", "message": "색인 결과를 확인하고 있습니다."}
            indexed = await invoke(status, {"owner": owner, "repo": repo})
            job.update({
                "status": "SUCCEEDED",
                "progress": {"stage": "DONE", "message": "재색인이 완료되었습니다."},
                "result": {
                    "indexed": int(synced.get("indexed") or 0),
                    "skipped": int(synced.get("skipped") or 0),
                    "total": int(synced.get("total") or 0),
                    "lastSyncedAt": indexed.get("last_synced_at") or synced.get("updated_at"),
                    "indexCount": int(indexed.get("count") or 0),
                },
            })
        except Exception as exc:  # noqa: BLE001
            error = error_from_exception(exc, MCP_UNAVAILABLE)
            log.warning("재색인 실패 %s/%s: [%s] %s", owner, repo, error.code, error.message)
            job.update({
                "status": "FAILED",
                "progress": {"stage": "FAILED", "message": "재색인에 실패했습니다."},
                "errorCode": error.code,
                "errorMessage": error.message,
            })
        finally:
            active_repositories.discard(key)

    @router.post("/reindex", status_code=202, summary="프로젝트 이슈 전체 재색인")
    async def request_reindex(req: ReindexRequest) -> dict[str, Any]:
        owner, repo = req.github_owner.strip(), req.github_repo.strip()
        if not owner or not repo:
            raise HTTPException(status_code=422, detail="githubOwner와 githubRepo는 비어 있을 수 없습니다")
        key = (owner.lower(), repo.lower())
        if key in active_repositories:
            raise HTTPException(status_code=409, detail="이 저장소는 이미 재색인 중입니다")
        job_id = str(uuid.uuid4())
        jobs[job_id] = {
            "jobId": job_id,
            "status": "RUNNING",
            "progress": {"stage": "QUEUED", "message": "재색인 작업을 접수했습니다."},
        }
        active_repositories.add(key)
        task = asyncio.create_task(execute(job_id, owner, repo))
        tasks.add(task)
        task.add_done_callback(tasks.discard)
        return {"jobId": job_id, "status": "ACCEPTED"}

    @router.get("/reindex/{job_id}", summary="프로젝트 재색인 상태 조회")
    async def get_reindex(job_id: str) -> dict[str, Any]:
        job = jobs.get(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="없는 재색인 작업입니다")
        return job

    return router
