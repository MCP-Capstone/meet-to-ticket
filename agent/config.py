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
    mcp_http_timeout_seconds: int = 10  # MCP 서버 연결·요청 전송 시간 한도
    # MCP 요청 하나(도구 호출, 도구 목록 조회)의 응답 대기 한도 (0 이하면 끔). 지정하지 않으면 끝없이 기다린다 (#299)
    mcp_call_timeout_seconds: int = 90
    # 일괄 발행 묶음 전체 마감 (0 이하면 끔). Backend 결과 대기 기한(PUBLISH_RESULT_TIMEOUT)보다 짧아야 한다
    publish_deadline_seconds: int = 600
    rag_sync_timeout_seconds: int = 30  # 실행마다 색인 갱신(sync_issues)을 기다리는 시간
    context_timeout_seconds: int = 120  # GitHub/Notion 조회의 실제 소요 시간을 포함하며, 넘으면 읽은 부분까지 사용
    tool_result_max_chars: int = 2000   # 모델에게 넘기는 도구 결과 길이 한도
    dedupe_model: str = "gpt-5.4-mini"  # 중복 판단(도구 고르기) 모델. 판단이 중요해 검수와 같은 모델
    dedupe_max_tool_calls: int = 4      # 티켓 하나에 모델이 부를 수 있는 도구 호출 수
    dedupe_max_same_tool: int = 3       # 같은 도구를 부를 수 있는 수
    dedupe_timeout_seconds: float = 30  # 티켓 하나의 판단 시간 한도
    dedupe_concurrency: int = 4         # 동시에 판단하는 티켓 수 (24건 회의도 작업 마감 안에 끝나게)
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
    # True면 Phase 1처럼 검수를 통과한 티켓을 바로 발행한다 (baseline 재현용).
    # 기본은 초안 저장에서 멈추고, 담당자가 확인한 티켓만 일괄 발행한다 (기획 문서, TASK-012)
    publish_on_run: bool = False
    fallback_publish: bool = True       # 보완 한도 초과 시 기본 초안으로 발행할지 (False면 NEEDS_CHECK)
    # publish_on_run을 켜려면 환경과 무관하게 명시적 승인(allow_auto_publish)이 함께 있어야 한다 (validate_settings)
    allow_auto_publish: bool = False


def context_timeout_seconds() -> int:
    """프로젝트 맥락 조회 전체 한도(초). Settings와 맥락 수집기가 같은 값을 쓰게 한 곳에서 읽는다."""
    return _int("CONTEXT_TIMEOUT_SECONDS", 120)


def _str(name: str, default: str) -> str:
    """비어 있는 값(예: OPENAI_REVIEW_MODEL=)은 설정하지 않은 것으로 본다."""
    raw = os.environ.get(name, "").strip()
    return raw or default


class SettingsError(RuntimeError):
    """기동을 막아야 하는 안전하지 않은 설정."""


def validate_settings(settings: Settings) -> None:
    """사람 확인 없는 자동 발행(PUBLISH_ON_RUN)은 환경과 무관하게 ALLOW_AUTO_PUBLISH 승인 없이 쓰지 못한다 (fail-closed).

    DRY_RUN이면 외부에 쓰지 않으므로 막지 않는다 (평가 실행기가 cli --dry-run을 쓴다).
    """
    if settings.publish_on_run and not settings.dry_run and not settings.allow_auto_publish:
        raise SettingsError("PUBLISH_ON_RUN=true는 ALLOW_AUTO_PUBLISH=true 승인 없이 사용할 수 없습니다 (DRY_RUN=true면 예외)")


def load_settings(validate: bool = True) -> Settings:
    settings = Settings(
        openai_model=_str("OPENAI_MODEL", "gpt-5.4-nano"),
        review_model=_str("OPENAI_REVIEW_MODEL", "gpt-5.4-mini"),
        reasoning_effort=_str("OPENAI_REASONING_EFFORT", "none"),
        review_reasoning_effort=_str("OPENAI_REVIEW_REASONING_EFFORT", ""),
        fallback_model=_str("OPENAI_FALLBACK_MODEL", ""),
        mcp_server_url=_str("MCP_SERVER_URL", "http://127.0.0.1:8000/mcp"),
        rag_enabled=_bool("RAG_ENABLED", False),
        rag_mcp_url=_str("RAG_MCP_URL", "http://127.0.0.1:8002/mcp"),
        mcp_http_timeout_seconds=_int("MCP_HTTP_TIMEOUT_SECONDS", 10),
        mcp_call_timeout_seconds=_int("MCP_CALL_TIMEOUT_SECONDS", 90),
        publish_deadline_seconds=_int("PUBLISH_DEADLINE_SECONDS", 600),
        rag_sync_timeout_seconds=_int("RAG_SYNC_TIMEOUT_SECONDS", 30),
        context_timeout_seconds=context_timeout_seconds(),
        tool_result_max_chars=_int("TOOL_RESULT_MAX_CHARS", 2000),
        dedupe_model=_str("OPENAI_DEDUPE_MODEL", "gpt-5.4-mini"),
        dedupe_max_tool_calls=_int("DEDUPE_MAX_TOOL_CALLS", 4),
        dedupe_max_same_tool=_int("DEDUPE_MAX_SAME_TOOL", 3),
        dedupe_timeout_seconds=_int("DEDUPE_TIMEOUT_SECONDS", 30),
        dedupe_concurrency=_int("DEDUPE_CONCURRENCY", 4),
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
        publish_on_run=_bool("PUBLISH_ON_RUN", False),
        fallback_publish=_bool("FALLBACK_PUBLISH", True),
        allow_auto_publish=_bool("ALLOW_AUTO_PUBLISH", False),
    )
    if validate:
        validate_settings(settings)
    return settings
