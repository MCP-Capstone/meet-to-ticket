"""서버 뼈대와 설정 테스트."""

import httpx
import pytest

import config
import server


@pytest.fixture
def anyio_backend():
    # list_tools()가 async라 anyio로 돌린다 (trio는 쓰지 않음)
    return "asyncio"


def test_default_settings(monkeypatch):
    # 값을 비워 두면 포트 8002, 기본 임베딩 모델, rag-mcp/chroma-data를 쓴다
    for name in ("MCP_HOST", "MCP_PORT", "EMBEDDING_MODEL", "CHROMA_PATH"):
        monkeypatch.setenv(name, "")
    s = config.load_settings()
    assert (s.host, s.port) == ("127.0.0.1", 8002)
    assert s.embedding_model == "text-embedding-3-small"
    assert s.chroma_path == config.BASE_DIR / "chroma-data"


def test_settings_from_env(monkeypatch, tmp_path):
    # .env(환경 변수) 값이 있으면 그 값을 쓴다
    monkeypatch.setenv("MCP_HOST", "0.0.0.0")
    monkeypatch.setenv("MCP_PORT", "9102")
    monkeypatch.setenv("CHROMA_PATH", str(tmp_path))
    s = config.load_settings()
    assert (s.host, s.port, s.chroma_path) == ("0.0.0.0", 9102, tmp_path)


def test_server_name():
    # Agent가 연결하는 서버 이름
    assert server.mcp.name == "meet-to-ticket-rag"


def test_real_network_is_blocked():
    # conftest가 실제 HTTP 호출을 막는지 확인한다
    with pytest.raises(RuntimeError, match="실제 네트워크 호출 금지"):
        httpx.get("https://api.github.com")
