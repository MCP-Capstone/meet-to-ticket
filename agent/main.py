"""Agent API 서버.

실행: uvicorn main:app --port 8001
- POST /runs            : 접수 후 백그라운드 처리 (202)
- POST /runs?wait=true  : 끝날 때까지 기다렸다가 결과 반환 (로컬 테스트용)
- GET  /runs/{task_id}  : 이 프로세스에서 처리한 작업의 상태·결과
                          처리 중이면 {"taskId", "status": "RUNNING", "stage"}, 끝나면 결과 전체
- GET  /health
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse

from config import load_env, load_settings  # noqa: E402

load_env()
from reporter import BackendReporter  # noqa: E402
from runner import run  # noqa: E402
from schemas import RunRequest  # noqa: E402
from state import STAGE_EXTRACTING  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("agent.api")

app = FastAPI(title="meet-to-ticket agent")
app.state.overrides = {}  # 테스트에서 llm, publisher, reporter, settings를 바꿔 넣는다

_jobs: dict[str, dict[str, Any]] = {}
_tasks: set[asyncio.Task] = set()


def _settings():
    return app.state.overrides.get("settings") or load_settings()


def _running(task_id: str) -> dict[str, Any]:
    return {"taskId": task_id, "status": "RUNNING", "stage": STAGE_EXTRACTING}


class JobReporter:
    """진행 단계를 GET /runs/{taskId}에도 보이게 하고, 백엔드 보고는 그대로 넘긴다."""

    def __init__(self, inner: Any):
        self.inner = inner

    async def progress(self, task_id: str, status: str, stage: str) -> None:
        job = _jobs.get(task_id)
        if job is not None and job.get("status") == "RUNNING":
            job["stage"] = stage
        await self.inner.progress(task_id, status, stage)

    async def result(self, task_id: str, payload: dict[str, Any]) -> bool:
        return await self.inner.result(task_id, payload)


async def _execute(req: RunRequest) -> dict[str, Any]:
    _jobs[req.task_id] = _running(req.task_id)
    settings = _settings()
    inner = app.state.overrides.get("reporter") or BackendReporter(settings.backend_base_url, settings.backend_token)
    try:
        result = await run(
            req,
            settings,
            llm=app.state.overrides.get("llm"),
            publisher=app.state.overrides.get("publisher"),
            reporter=JobReporter(inner),
        )
        data = result.model_dump(by_alias=True, mode="json")
    except Exception as exc:  # noqa: BLE001
        log.exception("작업 %s 처리 중 오류", req.task_id)
        data = {"taskId": req.task_id, "status": "FAILED", "errorCode": "INTERNAL_ERROR", "errorMessage": str(exc)}
    _jobs[req.task_id] = data
    return data


@app.post("/runs")
async def create_run(req: RunRequest, wait: bool = False):
    current = _jobs.get(req.task_id)
    if current and current.get("status") == "RUNNING":
        raise HTTPException(status_code=409, detail="이미 처리 중인 task_id입니다")
    if wait:
        return await _execute(req)
    _jobs[req.task_id] = _running(req.task_id)
    task = asyncio.create_task(_execute(req))
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return JSONResponse(status_code=202, content={"taskId": req.task_id, "status": "ACCEPTED"})


@app.get("/runs/{task_id}")
async def get_run(task_id: str):
    if task_id not in _jobs:
        raise HTTPException(status_code=404, detail="없는 task_id입니다")
    return _jobs[task_id]


@app.get("/health")
async def health():
    s = _settings()
    return {
        "status": "ok",
        "mcp_server_url": s.mcp_server_url,
        "backend_reporting": bool(s.backend_base_url),
        "dry_run": s.dry_run,
        "model": s.openai_model,
    }
