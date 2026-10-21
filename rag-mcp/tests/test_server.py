"""서버 뼈대와 설정 테스트."""

import threading

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
    for name in ("MCP_HOST", "MCP_PORT", "EMBEDDING_MODEL", "CHROMA_PATH", "RAG_DEFAULT_HOST", "RAG_DEFAULT_CHROMA_PATH"):
        monkeypatch.setenv(name, "")
    s = config.load_settings()
    assert (s.host, s.port) == ("127.0.0.1", 8002)
    assert s.embedding_model == "text-embedding-3-small"
    assert s.chroma_path == config.BASE_DIR / "chroma-data"


def test_container_defaults_survive_empty_values(monkeypatch):
    # 컨테이너 기본값(RAG_DEFAULT_*)이 있으면, .env의 빈 값(MCP_HOST=, CHROMA_PATH=)이 들어와도 그 기본값을 쓴다
    monkeypatch.setenv("RAG_DEFAULT_HOST", "0.0.0.0")
    monkeypatch.setenv("RAG_DEFAULT_CHROMA_PATH", "/data/chroma")
    monkeypatch.setenv("MCP_HOST", "")
    monkeypatch.setenv("CHROMA_PATH", "")
    s = config.load_settings()
    assert (s.host, str(s.chroma_path)) == ("0.0.0.0", "/data/chroma")


def test_settings_from_env(monkeypatch, tmp_path):
    # .env(환경 변수) 값이 있으면 그 값을 쓴다
    monkeypatch.setenv("MCP_HOST", "0.0.0.0")
    monkeypatch.setenv("MCP_PORT", "9102")
    monkeypatch.setenv("CHROMA_PATH", str(tmp_path))
    s = config.load_settings()
    assert (s.host, s.port, s.chroma_path) == ("0.0.0.0", 9102, tmp_path)


def test_store_is_created_once_under_concurrent_calls(monkeypatch, tmp_path):
    # 서버를 띄운 직후 검색이 동시에 들어와도 Chroma 클라이언트를 하나만 만들고 모두 성공한다
    monkeypatch.setenv("CHROMA_PATH", str(tmp_path / "chroma"))
    barrier = threading.Barrier(8)
    stores, errors = [], []

    def call():
        barrier.wait()
        try:
            store = server.get_store()
            store.exists("o", "r")
            stores.append(store)
        except Exception as exc:  # noqa: BLE001
            errors.append(exc)

    threads = [threading.Thread(target=call) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == [] and len({id(s) for s in stores}) == 1


def test_server_name():
    # Agent가 연결하는 서버 이름
    assert server.mcp.name == "meet-to-ticket-rag"


def test_real_network_is_blocked():
    # conftest가 실제 HTTP 호출을 막는지 확인한다
    with pytest.raises(RuntimeError, match="실제 네트워크 호출 금지"):
        httpx.get("https://api.github.com")
