"""모든 테스트에 공통으로 적용되는 설정.

server.py는 import할 때 실제 rag-mcp/.env를 읽어 토큰과 키가 환경 변수에 들어온다.
테스트가 실수로 실제 GitHub·OpenAI를 호출하지 않도록 토큰과 키를 지우고, 실제 HTTP 전송을 막는다.
(httpx.MockTransport로 만든 클라이언트는 영향을 받지 않는다. OpenAI 클라이언트도 httpx를 쓰므로 함께 막힌다)
"""

import httpx
import pytest


@pytest.fixture(autouse=True)
def skip_index_recheck_wait(monkeypatch):
    # recheck_empty 대기는 실제로 기다리지 않는다. 대기 시간을 확인하려는 테스트는 다시 덮어쓴다.
    import indexer

    monkeypatch.setattr(indexer, 'sleep', lambda seconds: None)


@pytest.fixture(autouse=True)
def block_real_network(monkeypatch):
    for name in ("GITHUB_TOKEN", "OPENAI_API_KEY"):
        monkeypatch.delenv(name, raising=False)
    # Chroma가 사용 통계를 외부로 보내지 않게 한다
    monkeypatch.setenv("ANONYMIZED_TELEMETRY", "False")

    def refuse(self, request):
        raise RuntimeError(f"테스트에서 실제 네트워크 호출 금지: {request.method} {request.url}")

    monkeypatch.setattr(httpx.HTTPTransport, "handle_request", refuse)
    monkeypatch.setattr(httpx.AsyncHTTPTransport, "handle_async_request", refuse)
