"""그래프 상태와 상수."""

from __future__ import annotations

from typing import Any, TypedDict

# 진행 단계 (ANALYSIS_TASKS.stage)
STAGE_EXTRACTING = "EXTRACTING"
STAGE_NORMALIZING = "NORMALIZING"  # 담당자·기한 정리, 이슈 초안 작성
STAGE_REVIEWING = "REVIEWING"
STAGE_PUBLISHING = "PUBLISHING"
STAGE_DONE = "DONE"

# 진행 화면 단계 (기획_v16 REQ-007). stage는 백엔드 TaskStage 값을 그대로 두고, 화면 단계와 세부 정보는 progress에 담는다
STEP_CONTEXT = "CONTEXT"          # 맥락 파악
STEP_EXTRACTING = "EXTRACTING"    # 티켓 추출 (초안 작성·검수 포함)
STEP_DEDUPING = "DEDUPING"        # 중복 검수
STEP_DISPATCHING = "DISPATCHING"  # 담당자에게 전송 (결과 보고)

# 회의록 처리는 발행하지 않고 초안으로 끝난다. 담당자가 확인한 티켓만 일괄 발행한다 (TASK-034)
PUBLISH_DRAFT = "DRAFT"

# 발행 대상 (기획_v16 1.7.1, REQ-021). 할 일은 GitHub 또는 Notion으로 분류하고, Slack은 회의당 공지 하나다
TARGET_GITHUB = "GITHUB"
TARGET_NOTION = "NOTION"
TARGET_SLACK = "SLACK"

# 작업 상태 (ANALYSIS_TASKS.status)
STATUS_RUNNING = "RUNNING"
STATUS_SUCCEEDED = "SUCCEEDED"
STATUS_FAILED = "FAILED"

# 실패 코드
LLM_ERROR = "LLM_ERROR"
NO_TICKETS = "NO_TICKETS"
MCP_UNAVAILABLE = "MCP_UNAVAILABLE"
TASK_TIMEOUT = "TASK_TIMEOUT"      # 작업 마감 시간을 넘김 (초안·검수 단계에 들어가기 전에 확인)
INPUT_TOO_LONG = "INPUT_TOO_LONG"  # 회의 원문이 길이 한도를 넘음 (LLM을 호출하지 않음)
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
    deadline_at: float | None   # 작업 마감 시각 (epoch 초). None이면 마감 없음
    rag: dict[str, Any]         # rag-mcp 상태 {"status": disabled|unavailable|ready, "sync": 색인 갱신 결과}
    meeting_id: str
    meeting_date: str | None
    original_content: str
    owner: str
    repo: str
    members: list[dict[str, Any]]
    # 초안 라벨 후보. Agent가 MCP list_labels로 저장소 라벨을 받아 채운다 (도구가 생기면 prepare에서).
    # 비어 있으면 설정 ISSUE_LABELS를 쓴다
    label_candidates: list[str]
    background: str
    meeting_title: str          # Slack 공지 제목에 쓴다
    # 프로젝트 맥락 (TASK-019). runner가 그래프 전에 불러온다
    context: dict[str, Any]     # {"issues": 색인 개수, "notionDocs": 기획 문서 수}. 모르면 None
    planning_docs: list[dict[str, str]]  # 읽은 Notion 기획 문서 {"title", "text"}. 초안 작성의 배경으로만 쓴다
    progress: dict[str, Any]    # 진행 화면용 세부 정보 (step, context, extraction, dedupe)
    decisions: list[dict[str, Any]]  # 결정 사항 (schemas.ExtractedDecision)
    open_questions: list[str]
    excluded: list[str]
    tickets: list[dict[str, Any]]
    stage: str
    error_code: str | None
    error_message: str | None
    result: dict[str, Any]
