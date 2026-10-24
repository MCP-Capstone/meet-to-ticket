"""LangGraph·FastAPI가 설치된 환경(agent/.venv)에서만 실행된다."""

import pytest

from conftest import PUBLISHED_10MIN, FakeLLM, FakePublisher, FakeReporter, make_request

pytest.importorskip("langgraph")


@pytest.mark.anyio
async def test_graph_matches_plain(settings):
    from graph import build_graph
    from conftest import run_plain
    from nodes import AgentNodes
    from runner import initial_state

    req = make_request()
    plain = await run_plain(AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings), initial_state(req))
    nodes = AgentNodes(FakeLLM(), FakePublisher(), FakeReporter(), settings)
    final = await build_graph(nodes).ainvoke(initial_state(req), config={"recursion_limit": 50})
    strip = lambda r: [(i["work_key"], i["review_status"], i["publish_status"]) for i in r["items"]]  # noqa: E731
    assert strip(final["result"]) == strip(plain["result"])


@pytest.mark.anyio
async def test_runner_with_fakes(settings):
    from runner import run

    llm = FakeLLM(review_plan={"10m1-a1": [[(7, "제목 모호")]]})
    result = await run(make_request(), settings, llm=llm, publisher=FakePublisher(), reporter=FakeReporter())
    assert result.status == "SUCCEEDED"
    assert result.summary["published"] == len(PUBLISHED_10MIN)


def test_api(settings):
    pytest.importorskip("fastapi")
    from fastapi.testclient import TestClient

    import main

    main.app.state.overrides = {
        "settings": settings,
        "llm": FakeLLM(),
        "publisher": FakePublisher(),
        "reporter": FakeReporter(),
    }
    client = TestClient(main.app)
    assert client.get("/health").json()["status"] == "ok"
    body = make_request().model_dump(by_alias=True, mode="json")
    assert "taskId" in body and "projectMembers" in body
    resp = client.post("/runs?wait=true", json=body)
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "SUCCEEDED" and len(data["items"]) == 8
    assert data["items"][0]["taskItemId"] and "publishStatus" in data["items"][0]
    assert client.get(f"/runs/{body['taskId']}").json()["status"] == "SUCCEEDED"
    main.app.state.overrides = {}


@pytest.mark.anyio
async def test_runner_mcp_unavailable(settings):
    from runner import run

    settings.mcp_server_url = "http://127.0.0.1:9/mcp"  # 아무것도 듣지 않는 포트
    settings.dry_run = False
    rep = FakeReporter()
    result = await run(make_request(), settings, llm=FakeLLM(), reporter=rep)
    assert result.status == "FAILED"
    assert result.error_code == "MCP_UNAVAILABLE"
    assert rep.results and rep.results[-1]["errorCode"] == "MCP_UNAVAILABLE"
