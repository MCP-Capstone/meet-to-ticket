"""POST /projects/check-members: MCP 도구 결과 전달과 전체 오류 코드 보존."""

from __future__ import annotations

import json

import pytest
from fastapi.testclient import TestClient

import main

BODY = {"githubOwner": "MCP-Capstone", "githubRepo": "mcp-capstone", "githubNames": ["minjun-k", "seoyeon-p"]}


class FakeTool:
    def __init__(self, result=None, error: Exception | None = None):
        self.result, self.error, self.calls = result, error, []

    async def ainvoke(self, args):
        self.calls.append(args)
        if self.error:
            raise self.error
        return json.dumps(self.result)


@pytest.fixture
def client(settings):
    yield TestClient(main.app)
    main.app.state.overrides = {}


def use(settings, tool):
    main.app.state.overrides = {"settings": settings, "mcp_tools": {"check_member_permissions": tool}}


def test_멤버별_권한을_camelCase로_돌려준다(client, settings):
    tool = FakeTool({"members": [{"github_name": "minjun-k", "permission": "GRANTED"},
                                 {"github_name": "seoyeon-p", "permission": "ERROR"}]})
    use(settings, tool)
    resp = client.post("/projects/check-members", json=BODY)
    assert resp.status_code == 200
    assert resp.json() == {"members": [{"githubName": "minjun-k", "permission": "GRANTED"},
                                       {"githubName": "seoyeon-p", "permission": "ERROR"}]}
    assert tool.calls == [{"owner": "MCP-Capstone", "repo": "mcp-capstone", "github_names": ["minjun-k", "seoyeon-p"]}]


def test_MCP_전체_오류는_코드를_그대로_전달한다(client, settings):
    use(settings, FakeTool(error=RuntimeError("[REPOSITORY_NOT_ALLOWED] 허용되지 않은 저장소")))
    resp = client.post("/projects/check-members", json=BODY)
    assert resp.status_code == 502
    assert resp.json()["errorCode"] == "REPOSITORY_NOT_ALLOWED"


def test_MCP에_연결하지_못하면_MCP_UNAVAILABLE이다(client, settings):
    settings.mcp_server_url = "http://127.0.0.1:9/mcp"
    main.app.state.overrides = {"settings": settings}
    resp = client.post("/projects/check-members", json=BODY)
    assert resp.status_code == 502
    assert resp.json()["errorCode"] == "MCP_UNAVAILABLE"


def test_계정이_없으면_MCP를_부르지_않는다(client, settings):
    tool = FakeTool({"members": []})
    use(settings, tool)
    resp = client.post("/projects/check-members", json={**BODY, "githubNames": []})
    assert resp.json() == {"members": []}
    assert tool.calls == []
