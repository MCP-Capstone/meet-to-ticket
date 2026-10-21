"""Agent API 서버.

실행: uvicorn main:app --port 8001
- POST /runs            : 접수 후 백그라운드 처리 (202)
- POST /runs?wait=true  : 끝날 때까지 기다렸다가 결과 반환 (로컬 테스트용)
- GET  /runs/{task_id}  : 이 프로세스에서 처리한 작업의 상태·결과
                          처리 중이면 {"taskId", "status": "RUNNING", "stage"}, 끝나면 결과 전체
- GET  /runs/{task_id}/history : 노드별 실행 기록 (checkpointer)
- GET  /health

이슈를 발행한 taskId를 다시 POST하면 다시 실행하지 않고 저장된 결과를 돌려준다 (runner.stored_result).
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import Body, FastAPI, HTTPException, Path, Query
from fastapi.responses import JSONResponse

from config import load_env, load_settings  # noqa: E402

load_env()
from reporter import BackendReporter  # noqa: E402
from runner import history, run, stored_result  # noqa: E402
from schemas import RunRequest  # noqa: E402
from state import STAGE_EXTRACTING  # noqa: E402
from api_docs import (  # noqa: E402
    DESCRIPTION, HEALTH_RESPONSES, HISTORY_RESPONSES, JOB_RESPONSES, REQUEST_EXAMPLE, RUN_RESPONSES,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("agent.api")

app = FastAPI(
    title="Meet-to-Ticket — Agent API",
    version="1.0",
    description=DESCRIPTION,
    openapi_tags=[{"name": "분석 실행", "description": "Spring에서 호출하는 작업 접수·조회"},
                  {"name": "상태 확인", "description": "Agent 설정과 실행 상태"}],
)
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


@app.post(
    "/runs", tags=["분석 실행"], operation_id="createAgentRun",
    summary="분석·검수·발행 작업 접수", response_model=None, responses=RUN_RESPONSES,
    description="기본 응답은 202. wait=true일 때만 200으로 완료까지 기다린다. GET으로 상태를 확인하며 POST를 반복하지 않는다. 요청 형식 위반은 FastAPI 기본 422 응답이다.",
)
async def create_run(
    req: RunRequest = Body(openapi_examples={"meeting": {"summary": "회의·프로젝트·팀원 JSON", "value": REQUEST_EXAMPLE}}),
    wait: bool = Query(False, description="로컬 테스트용 동기 실행. Spring은 false(기본값) 사용"),
):
    current = _jobs.get(req.task_id)
    if current and current.get("status") == "RUNNING":
        raise HTTPException(status_code=409, detail="이미 처리 중인 task_id입니다")
    previous = await stored_result(req.task_id)
    if previous is not None:
        # 이미 이슈를 발행한 작업은 다시 실행하지 않고 저장된 결과를 GET으로 돌려준다 (중복 이슈 방지)
        _jobs[req.task_id] = previous.model_dump(by_alias=True, mode="json")
        if wait:
            return _jobs[req.task_id]
        return JSONResponse(status_code=202, content={"taskId": req.task_id, "status": "ACCEPTED"})
    if wait:
        return await _execute(req)
    _jobs[req.task_id] = _running(req.task_id)
    task = asyncio.create_task(_execute(req))
    _tasks.add(task)
    task.add_done_callback(_tasks.discard)
    return JSONResponse(status_code=202, content={"taskId": req.task_id, "status": "ACCEPTED"})


@app.get(
    "/runs/{task_id}", tags=["분석 실행"], operation_id="getAgentRun",
    summary="진행·최종 결과 조회", response_model=None, responses=JOB_RESPONSES,
    description="기존 메모리 작업만 조회한다. RUNNING이면 taskId·status와 지금 진행 단계(stage)를 반환한다. 종료 시 RunResult 또는 예외 결과를 반환하며, 이 GET은 재실행하지 않는다.",
)
async def get_run(task_id: str = Path(description="POST에 보낸 taskId. Agent는 문자열을 받으며 Spring은 UUID 사용", examples=["9b12ec5a-1423-4b71-8e20-f8f28d306c81"])):
    if task_id not in _jobs:
        raise HTTPException(status_code=404, detail="없는 task_id입니다")
    return _jobs[task_id]


@app.get(
    "/runs/{task_id}/history", tags=["분석 실행"], operation_id="getAgentRunHistory",
    summary="노드별 실행 기록 조회", response_model=None, responses=HISTORY_RESPONSES,
    description="노드가 끝날 때마다 저장된 상태(checkpoint)를 시간 순서로 요약한다. 이 프로세스 메모리에만 있어 재시작하면 사라진다.",
)
async def get_run_history(task_id: str = Path(description="POST에 보낸 taskId", examples=["9b12ec5a-1423-4b71-8e20-f8f28d306c81"])):
    steps = await history(task_id)
    if steps is None:
        raise HTTPException(status_code=404, detail="없는 task_id입니다")
    return {"taskId": task_id, "steps": steps}


@app.get("/health", tags=["상태 확인"], operation_id="getAgentHealth", summary="Agent 상태·설정 조회",
         response_model=None, responses=HEALTH_RESPONSES)
async def health():
    s = _settings()
    return {
        "status": "ok",
        "mcp_server_url": s.mcp_server_url,
        "backend_reporting": bool(s.backend_base_url),
        "dry_run": s.dry_run,
        "model": s.openai_model,
    }
