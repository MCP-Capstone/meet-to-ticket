"""수동 RAG 재색인 라우터. main 등록 전에도 라우터 계약을 직접 검증한다."""

from __future__ import annotations

import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from config import Settings
from reindex import create_router


class FakeTool:
    def __init__(self, result):
        self.result = result
        self.calls = []

    async def ainvoke(self, args):
        self.calls.append(args)
        return json.dumps(self.result)


def test_전체_재색인을_접수하고_결과를_조회한다():
    sync = FakeTool({"indexed": 3, "skipped": 1, "total": 4, "updated_at": "2026-10-23T06:00:00Z"})
    status = FakeTool({"count": 4, "last_synced_at": "2026-10-23T06:00:00Z"})
    app = FastAPI()
    app.include_router(create_router(lambda: Settings(rag_enabled=True), lambda: {"rag_tools": {"sync_issues": sync, "index_status": status}}))
    with TestClient(app) as client:
        accepted = client.post("/projects/reindex", json={"githubOwner": "MCP-Capstone", "githubRepo": "meet-to-ticket"})
        assert accepted.status_code == 202
        job_id = accepted.json()["jobId"]
        result = client.get(f"/projects/reindex/{job_id}")
        assert result.status_code == 200
        assert result.json()["status"] == "SUCCEEDED"
        assert result.json()["result"] == {
            "indexed": 3, "skipped": 1, "total": 4,
            "lastSyncedAt": "2026-10-23T06:00:00Z", "indexCount": 4,
        }
    assert sync.calls == [{"owner": "MCP-Capstone", "repo": "meet-to-ticket", "full": True}]
    assert status.calls == [{"owner": "MCP-Capstone", "repo": "meet-to-ticket"}]


def test_RAG가_꺼져있으면_실패_상태를_돌려준다():
    app = FastAPI()
    app.include_router(create_router(lambda: Settings(rag_enabled=False)))
    with TestClient(app) as client:
        job_id = client.post("/projects/reindex", json={"githubOwner": "o", "githubRepo": "r"}).json()["jobId"]
        result = client.get(f"/projects/reindex/{job_id}").json()
    assert result["status"] == "FAILED"
    assert result["errorCode"] == "RAG_DISABLED"
