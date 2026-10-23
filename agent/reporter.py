"""백엔드 진행·결과 보고.

BACKEND_BASE_URL이 비어 있으면 아무것도 보내지 않는다(로컬 테스트용).
경로는 백엔드와 확정 전이라 아래 기본값을 쓰고, 바뀌면 이 파일만 고친다.
    진행: POST {base}/internal/tasks/{task_id}/progress   {"status", "stage", "progress"}
    결과: POST {base}/internal/tasks/{task_id}/result     RunResult
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

log = logging.getLogger("agent.reporter")

PROGRESS_PATH = "/internal/tasks/{task_id}/progress"
RESULT_PATH = "/internal/tasks/{task_id}/result"
PUBLISH_RESULT_PATH = "/internal/publish-batches/{batch_id}/result"


class BackendReporter:
    def __init__(self, base_url: str, token: str = "", client: Any | None = None):
        self.base_url = (base_url or "").rstrip("/")
        self.token = token
        self._client = client

    @property
    def enabled(self) -> bool:
        return bool(self.base_url)

    def _headers(self) -> dict[str, str]:
        return {"X-Internal-Token": self.token} if self.token else {}

    async def _post(self, path: str, payload: dict[str, Any], retries: int) -> bool:
        if not self.enabled:
            return False
        import httpx

        url = self.base_url + path
        for attempt in range(retries + 1):
            try:
                if self._client is not None:
                    resp = await self._client.post(url, json=payload, headers=self._headers())
                else:
                    async with httpx.AsyncClient(timeout=10.0) as client:
                        resp = await client.post(url, json=payload, headers=self._headers())
                if resp.status_code < 300:
                    return True
                log.warning("백엔드 보고 실패 %s %s: %s", url, resp.status_code, resp.text[:200])
            except Exception as exc:  # noqa: BLE001
                log.warning("백엔드 보고 오류 %s (%d회차): %s", url, attempt + 1, exc)
            await asyncio.sleep(1.0 * (attempt + 1))
        return False

    async def progress(self, task_id: str, status: str, stage: str, progress: dict[str, Any] | None = None) -> None:
        payload: dict[str, Any] = {"status": status, "stage": stage}
        if progress is not None:
            payload["progress"] = progress
        await self._post(PROGRESS_PATH.format(task_id=task_id), payload, retries=0)

    async def result(self, task_id: str, payload: dict[str, Any]) -> bool:
        return await self._post(RESULT_PATH.format(task_id=task_id), payload, retries=3)

    async def publish_result(self, batch_id: str, payload: dict[str, Any]) -> bool:
        """일괄 발행 결과는 분석 작업 결과와 분리된 백엔드 내부 경로로 보고한다."""
        return await self._post(PUBLISH_RESULT_PATH.format(batch_id=batch_id), payload, retries=3)

