"""문서 생성·예시와 실제 응답 계약을 외부 서비스 호출 없이 검증한다."""

from fastapi.testclient import TestClient

from conftest import FakeLLM, FakePublisher, FakeReporter, make_request
from api_docs import END_EXAMPLE, HISTORY_EXAMPLE, REQUEST_EXAMPLE, RunHistory
from schemas import RunRequest, RunResult


def test_agent_openapi_matches_implemented_routes():
    import main
    with TestClient(main.app) as client:
        assert client.get("/docs").status_code == 200
        doc = client.get("/openapi.json").json()
    assert set(doc["paths"]) == {
        "/runs", "/runs/{task_id}", "/runs/{task_id}/history", "/projects/check-members",
        "/publication-preview", "/projects/check-connections", "/projects/reindex", "/projects/reindex/{job_id}", "/projects/drop-index", "/publish", "/health",
    }
    assert not doc.get("security")  # 현 구현에 없는 API 키 인증을 문서에 가장하지 않음
    post = doc["paths"]["/runs"]["post"]
    assert {"200", "202", "409", "422"} <= post["responses"].keys()
    assert "anyOf" in doc["paths"]["/runs/{task_id}"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
    assert doc["components"]["schemas"]["RunResult"]["properties"]["status"]["enum"] == ["SUCCEEDED", "FAILED"]
    assert "taskItemId" in doc["components"]["schemas"]["TaskItemOut"]["properties"]
    # 실행 중 응답에는 진행 단계가 있고, 결과에는 traceId·metrics가 있다
    running = doc["components"]["schemas"]["RunProgress"]
    assert "stage" in running["required"]
    assert running["properties"]["stage"]["enum"] == ["EXTRACTING", "NORMALIZING", "REVIEWING", "PUBLISHING"]
    assert {"traceId", "metrics"} <= doc["components"]["schemas"]["RunResult"]["properties"].keys()
    RunRequest.model_validate(REQUEST_EXAMPLE)
    RunResult.model_validate(END_EXAMPLE)
    RunHistory.model_validate(HISTORY_EXAMPLE)
    assert "404" in doc["paths"]["/runs/{task_id}/history"]["get"]["responses"]


def test_documented_errors_and_wait_result(settings):
    import main
    old_overrides = main.app.state.overrides
    old_jobs = dict(main._jobs)
    try:
        main._jobs.clear()
        publisher = FakePublisher()
        main.app.state.overrides = {"settings": settings, "llm": FakeLLM(),
                                    "publisher": publisher, "reporter": FakeReporter()}
        with TestClient(main.app) as client:
            assert client.get("/runs/missing").json() == {"detail": "없는 task_id입니다"}
            assert client.get("/runs/missing").status_code == 404
            assert client.post("/runs", json={}).status_code == 422
            req = make_request().model_dump(by_alias=True, mode="json")
            main._jobs[req["taskId"]] = {"taskId": req["taskId"], "status": "RUNNING"}
            assert client.post("/runs", json=req).status_code == 409
            main._jobs.clear()
            result = client.post("/runs?wait=true", json=req)
            assert result.status_code == 200
            RunResult.model_validate(result.json())
            published = len(publisher.calls)
            # 이미 발행까지 끝난 taskId는 202로 받되 다시 실행하지 않는다 (중복 이슈 방지)
            accepted = client.post("/runs", json=req)
            assert accepted.status_code == 202
            assert accepted.json() == {"taskId": req["taskId"], "status": "ACCEPTED"}
            assert len(publisher.calls) == published
            assert client.get(f"/runs/{req['taskId']}").json() == result.json()
            assert client.get("/runs/missing/history").status_code == 404
            RunHistory.model_validate(client.get(f"/runs/{req['taskId']}/history").json())
    finally:
        main.app.state.overrides = old_overrides
        main._jobs.clear()
        main._jobs.update(old_jobs)
