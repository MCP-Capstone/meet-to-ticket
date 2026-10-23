"""rag-mcp 설정.

값은 rag-mcp/.env에서 읽는다 (server.py가 시작할 때 load_dotenv). 터미널에 같은 이름의 환경 변수가 있어도 .env가 우선한다.
load_settings()는 부를 때마다 환경 변수를 다시 읽으므로, 테스트에서 monkeypatch로 값을 바꿀 수 있다.

컨테이너 기본값
- Dockerfile은 RAG_DEFAULT_HOST(0.0.0.0), RAG_DEFAULT_CHROMA_PATH(볼륨 경로)를 정해 둔다.
- MCP_HOST, CHROMA_PATH가 비어 있으면 이 값을 쓴다. .env.example처럼 빈 값(MCP_HOST=)이 들어 있는 파일을
  --env-file로 넣어도 컨테이너 기본값이 지워지지 않게 하기 위해서다.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 8002  # docs/dev-env.md 포트 규칙 (business-mcp 8000, agent 8001)
DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"
DEFAULT_CHROMA_PATH = BASE_DIR / "chroma-data"


def _str(name: str, default: str = "") -> str:
    """비어 있는 값(예: MCP_PORT=)은 설정하지 않은 것으로 본다."""
    return os.environ.get(name, "").strip() or default


@dataclass(frozen=True)
class Settings:
    github_token: str
    github_owner: str
    github_repo: str
    github_allowed_repos: str  # 쉼표 구분 owner/repo. 비어 있으면 저장소를 검사하지 않음 (로컬 테스트용 안전장치)
    openai_api_key: str
    embedding_model: str
    chroma_path: Path
    host: str
    port: int


def load_settings() -> Settings:
    default_host = _str("RAG_DEFAULT_HOST", DEFAULT_HOST)
    default_chroma = _str("RAG_DEFAULT_CHROMA_PATH", str(DEFAULT_CHROMA_PATH))
    return Settings(
        github_token=_str("GITHUB_TOKEN"),
        github_owner=_str("GITHUB_OWNER"),
        github_repo=_str("GITHUB_REPO"),
        github_allowed_repos=_str("GITHUB_ALLOWED_REPOS"),
        openai_api_key=_str("OPENAI_API_KEY"),
        embedding_model=_str("EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL),
        chroma_path=Path(_str("CHROMA_PATH", default_chroma)),
        host=_str("MCP_HOST", default_host),
        port=int(_str("MCP_PORT", str(DEFAULT_PORT))),
    )
