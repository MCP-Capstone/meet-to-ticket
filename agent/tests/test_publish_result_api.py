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
