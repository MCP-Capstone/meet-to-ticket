"""모든 테스트에 공통으로 적용되는 설정.

server.py는 import할 때 실제 mcp-server/.env를 읽어 토큰과 저장소가 환경 변수에 들어온다.
테스트가 실수로 실제 GitHub를 호출하지 않도록 토큰을 지우고, 실제 HTTP 전송을 막는다.
(httpx.MockTransport로 만든 클라이언트는 영향을 받지 않는다)
"""

import httpx
import pytest


@pytest.fixture(autouse=True)
def block_real_github(monkeypatch):
    monkeypatch.delenv("GITHUB_TOKEN", raising=False)

    def refuse(self, request):
        raise RuntimeError(f"테스트에서 실제 네트워크 호출 금지: {request.method} {request.url}")

    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", refuse)
