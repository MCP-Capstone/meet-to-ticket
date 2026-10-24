from fastapi.testclient import TestClient


def test_publish_status_only_reads_existing_result(monkeypatch):
    import main

    jobs = {"test-batch": {"batchId": "test-batch", "status": "RUNNING"}}
    monkeypatch.setattr(main, "_publish_jobs", jobs)
    client = TestClient(main.app)
    assert client.get("/publish/test-batch").json()["status"] == "RUNNING"
    jobs["test-batch"] = {"batchId": "test-batch", "status": "FAILED", "items": []}
    assert client.get("/publish/test-batch").json()["status"] == "FAILED"
    assert client.get("/publish/missing").status_code == 404
    assert list(jobs) == ["test-batch"]


def test_restarted_agent_cannot_tell_lost_batch_from_unknown_batch(monkeypatch):
    """발행 묶음 기록은 프로세스 메모리에만 있어, 재시작하면 처리 중이던 묶음도 처음 보는 batchId와 같은 404가 된다.

    Backend는 접수 유예 시간이 지난 묶음의 404를 "Agent가 묶음을 잃음"으로 보고 바로 결과 모름(RESULT_LOST)으로
    끝낸다 (#302, backend PublishBatchTest). #299에서는 결과 대기 기한(30분)까지 기다렸다.
    """
    import main

    monkeypatch.setattr(main, "_publish_jobs", {"running": {"batchId": "running", "status": "RUNNING"}})
    client = TestClient(main.app)
    assert client.get("/publish/running").json()["status"] == "RUNNING"

    monkeypatch.setattr(main, "_publish_jobs", {})  # 재시작
    lost, never_sent = client.get("/publish/running"), client.get("/publish/never-sent")
    assert lost.status_code == never_sent.status_code == 404
    assert lost.json() == never_sent.json()
