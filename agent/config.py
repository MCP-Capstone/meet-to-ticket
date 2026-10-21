"""에이전트 설정. 모든 값은 환경 변수(.env)에서 읽는다."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ENV_PATH = Path(__file__).resolve().parent / ".env"


def load_env() -> bool:
    """agent/.env를 읽는다. 터미널에 같은 이름의 환경 변수가 있어도 .env 값이 우선한다."""
    from dotenv import load_dotenv

    return load_dotenv(ENV_PATH, override=True)


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    return int(raw) if raw else default


def _list(name: str) -> list[str]:
    return [x.strip() for x in os.environ.get(name, "").split(",") if x.strip()]


@dataclass
class Settings:
    openai_model: str = "gpt-5.4-nano"  # 추출·초안
    review_model: str = "gpt-5.4-mini"  # 검수 (판단이 중요한 작업이라 한 단계 큰 모델)
    reasoning_effort: str = "none"      # 추출·초안: 추론을 끄고 temperature 0으로 결과를 고정
    review_reasoning_effort: str = ""   # 검수: 비어 있으면 모델 기본 추론 (temperature는 1)
    fallback_model: str = ""            # 일시적 오류가 재시도 후에도 계속될 때 쓸 대체 모델 (비면 끔)
    mcp_server_url: str = "http://127.0.0.1:8000/mcp"
    rag_enabled: bool = False           # rag-mcp(기존 이슈 색인·검색)를 쓸지. 끄면 중복 판단 없이 발행
    rag_mcp_url: str = "http://127.0.0.1:8002/mcp"
    rag_sync_timeout_seconds: int = 30  # 실행마다 색인 갱신(sync_issues)을 기다리는 시간
    tool_result_max_chars: int = 2000   # 모델에게 넘기는 도구 결과 길이 한도
    backend_base_url: str = ""          # 비어 있으면 진행·결과 보고를 하지 않는다
    backend_token: str = ""             # 백엔드 내부 API 서비스 토큰
    issue_labels: list[str] = field(default_factory=list)
    max_revisions: int = 2              # 검수 보완 최대 횟수
    llm_retries: int = 2                # LLM 호출 재시도 횟수
    llm_timeout_seconds: int = 120      # LLM 호출 1회 시간 한도 (추론 모델이라 넉넉히)
    task_deadline_seconds: int = 600    # 작업 마감. 넘으면 다음 초안·검수 단계에 들어가지 않는다 (0 이하면 끔)
    max_source_chars: int = 40000       # 회의 원문 길이 한도. 넘으면 LLM을 호출하지 않는다 (0 이하면 끔)
    review_batch_size: int = 10         # 한 번에 검수할 티켓 수
    draft_batch_size: int = 10          # 한 번에 작성할 티켓 수
    conditional_as_needs_check: bool = True  # "조건부 기존 이슈 갱신"을 확인 필요로 둘지
    dry_run: bool = False               # True면 GitHub에 발행하지 않는다
    fallback_publish: bool = True       # 보완 한도 초과 시 기본 초안으로 발행할지 (False면 NEEDS_CHECK)


def _str(name: str, default: str) -> str:
    """비어 있는 값(예: OPENAI_REVIEW_MODEL=)은 설정하지 않은 것으로 본다."""
    raw = os.environ.get(name, "").strip()
    return raw or default


def load_settings() -> Settings:
    return Settings(
        openai_model=_str("OPENAI_MODEL", "gpt-5.4-nano"),
        review_model=_str("OPENAI_REVIEW_MODEL", "gpt-5.4-mini"),
        reasoning_effort=_str("OPENAI_REASONING_EFFORT", "none"),
        review_reasoning_effort=_str("OPENAI_REVIEW_REASONING_EFFORT", ""),
        fallback_model=_str("OPENAI_FALLBACK_MODEL", ""),
        mcp_server_url=_str("MCP_SERVER_URL", "http://127.0.0.1:8000/mcp"),
        rag_enabled=_bool("RAG_ENABLED", False),
        rag_mcp_url=_str("RAG_MCP_URL", "http://127.0.0.1:8002/mcp"),
        rag_sync_timeout_seconds=_int("RAG_SYNC_TIMEOUT_SECONDS", 30),
        tool_result_max_chars=_int("TOOL_RESULT_MAX_CHARS", 2000),
        backend_base_url=_str("BACKEND_BASE_URL", "").rstrip("/"),
        backend_token=_str("BACKEND_INTERNAL_TOKEN", ""),
        issue_labels=_list("ISSUE_LABELS"),
        max_revisions=_int("MAX_REVISIONS", 2),
        llm_retries=_int("LLM_RETRIES", 2),
        llm_timeout_seconds=_int("LLM_TIMEOUT_SECONDS", 120),
        task_deadline_seconds=_int("TASK_DEADLINE_SECONDS", 600),
        max_source_chars=_int("MAX_SOURCE_CHARS", 40000),
        review_batch_size=_int("REVIEW_BATCH_SIZE", 10),
        draft_batch_size=_int("DRAFT_BATCH_SIZE", 10),
        conditional_as_needs_check=_bool("CONDITIONAL_AS_NEEDS_CHECK", True),
        dry_run=_bool("DRY_RUN", False),
        fallback_publish=_bool("FALLBACK_PUBLISH", True),
    )
