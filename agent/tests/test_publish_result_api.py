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
    """#302에서 바꿀 현재 동작 (#299): 발행 묶음 기록은 프로세스 메모리에만 있다.

    재시작하면 처리 중이던 묶음도 처음 보는 batchId와 같은 404가 되고, Backend는 이를 결과 없음으로 보고
    결과 대기 기한(기본 30분)까지 기다린다 (backend PublishBatchTest).
    """
    import main

    monkeypatch.setattr(main, "_publish_jobs", {"running": {"batchId": "running", "status": "RUNNING"}})
    client = TestClient(main.app)
    assert client.get("/publish/running").json()["status"] == "RUNNING"

    monkeypatch.setattr(main, "_publish_jobs", {})  # 재시작
    lost, never_sent = client.get("/publish/running"), client.get("/publish/never-sent")
    assert lost.status_code == never_sent.status_code == 404
    assert lost.json() == never_sent.json()
