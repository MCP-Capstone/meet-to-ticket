"""그래프 상태와 상수."""

from __future__ import annotations

from typing import Any, TypedDict

# 진행 단계 (ANALYSIS_TASKS.stage)
STAGE_EXTRACTING = "EXTRACTING"
STAGE_NORMALIZING = "NORMALIZING"  # 담당자·기한 정리, 이슈 초안 작성
STAGE_REVIEWING = "REVIEWING"
STAGE_PUBLISHING = "PUBLISHING"
STAGE_DONE = "DONE"

# 작업 상태 (ANALYSIS_TASKS.status)
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"

# 실패 코드
LLM_ERROR = "LLM_ERROR"
NO_TICKETS = "NO_TICKETS"
MCP_UNAVAILABLE = "MCP_UNAVAILABLE"
REPOSITORY_NOT_ALLOWED = "REPOSITORY_NOT_ALLOWED"
GITHUB_AUTH = "GITHUB_AUTH"
PUBLISH_FAILED = "PUBLISH_FAILED"
PUBLISH_UNKNOWN = "PUBLISH_UNKNOWN"

# 이 코드가 나오면 남은 발행을 멈춘다
FATAL_PUBLISH_CODES = {REPOSITORY_NOT_ALLOWED, GITHUB_AUTH}

# 검수 점검 항목
REJECT_ITEMS = {1}                # 실패 시 반려 (근거 액션 아이템 없음)
SCOPE_ITEMS = {2, 3}              # 범위 의심: 보완 한도 후에도 남으면 LLM 추출 티켓은 확인 필요
LLM_ITEMS = {2, 3, 4, 7, 8}       # LLM이 판정하는 항목
ADVISORY_ITEMS = {7, 8}           # 참고용: 발행을 막지 않고 메모로만 남김
CHECK_NAMES = {
    1: "근거 액션 아이템 존재",
    2: "제외·보류 항목이 아님",
    3: "할 일이 맞음",
    4: "원문에 없는 내용 없음",
    7: "제목이 명확함",
    8: "확인 가능한 완료 조건",
    9: "본문 필수 칸",
}

# 티켓 내부 검수 상태 (REVISE는 루프 중에만 쓰고 결과에는 나가지 않는다)
REVIEW_PENDING = "PENDING"
REVIEW_REVISE = "REVISE"
REVIEW_APPROVED = "APPROVED"
REVIEW_REJECTED = "REJECTED"
REVIEW_NEEDS_CHECK = "NEEDS_CHECK"


class AgentFatal(Exception):
    """작업 전체를 멈추는 오류."""

    def __init__(self, code: str, message: str):
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message


class AgentState(TypedDict, total=False):
    task_id: str
    trace_id: str | None        # LangSmith run id (결과의 traceId)
    started_at: float           # 작업 시작 시각 (epoch 초, 소요 시간 계산용)
    meeting_id: str
    meeting_date: str | None
    original_content: str
    owner: str
    repo: str
    members: list[dict[str, Any]]
    background: str
    open_questions: list[str]
    excluded: list[str]
    tickets: list[dict[str, Any]]
    stage: str
    error_code: str | None
    error_message: str | None
    result: dict[str, Any]
