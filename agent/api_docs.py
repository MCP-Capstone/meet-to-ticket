"""현재 /runs API의 OpenAPI 메타데이터. 실행·인증 동작은 변경하지 않는다."""

from typing import Literal

from pydantic import Field

from schemas import CamelModel, RunResult

DESCRIPTION = """
Spring이 호출하는 Agent API. Spring의 공개 API와는 다른 서버에서 제공한다.

- POST /runs는 기본적으로 202 ACCEPTED를 반환하고 백그라운드에서 처리한다.
- GET /runs/{task_id}로 RUNNING 또는 종료 결과 SUCCEEDED / FAILED를 조회한다.
  RUNNING이면 진행 단계(stage)가 EXTRACTING → NORMALIZING → REVIEWING → PUBLISHING 순서로 바뀐다.
- SUCCEEDED는 Agent 실행 완료이며, 모든 GitHub 발행 성공을 뜻하지 않는다. items와 summary를 함께 확인한다.
- wait=true는 로컬 테스트용 동기 실행이며 HTTP 200으로 최종 결과를 반환한다.
- 생성·조회 상태는 메모리에 있어 프로세스 재시작 시 사라진다.
- 같은 taskId가 RUNNING이면 409로 차단한다.
- 이미 끝난 taskId를 다시 보내면, 이슈를 발행했거나 발행 여부가 불명인 작업은 다시 실행하지 않고(중복 이슈 방지)
  202로 받은 뒤 GET에서 저장된 결과를 돌려준다. 발행 전에 실패한 작업은 다시 실행한다.
- GET /runs/{task_id}/history로 노드별 실행 기록(checkpoint)을 볼 수 있다.
- 현재 API는 X-Internal-Api-Key 인증을 검사하지 않는다. Spring 세션 인증과 혼동하지 않는다.
- MD v0.2의 /internal/v1/analysis-tasks와 오류 envelope는 현재 미구현이다.
- DRY_RUN=false에서 POST는 실제 LLM 호출·GitHub 이슈 생성으로 이어질 수 있다.
  문서 확인은 외부 호출을 하지 않는다. 단독 테스트는 DRY_RUN=true와 BACKEND_BASE_URL 미설정을 권장한다.
"""

REQUEST_EXAMPLE = {
    "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
    "meeting": {
        "meetingId": 2, "title": "CSV 중간 점검", "meetingDate": "2026-10-22",
        "timezone": "Asia/Seoul", "participantIds": [11], "originalFilename": "meeting.txt",
        "originalContent": "# CSV 중간 점검\n회의 날짜: 2026-10-22\n참여자: 박종민\n박종민이 내일 오후 5시까지 CSV 빈 결과 오류를 수정한다.",
    },
    "project": {"projectId": 1, "name": "CSV 내보내기", "githubOwner": "MCP-Capstone", "githubRepo": "mcp-capstone"},
    "projectMembers": [{"projectMemberId": 11, "userId": None, "name": "박종민", "githubName": None}],
}


class RunAccepted(CamelModel):
    task_id: str = Field(description="요청과 같은 taskId. Spring에서는 UUID를 발급한다.")
    status: Literal["ACCEPTED"] = Field(description="접수만 완료. GitHub 생성 성공이 아님")


class RunProgress(CamelModel):
    task_id: str = Field(description="기존 작업 ID")
    status: Literal["RUNNING"] = Field(description="처리 중. 항목·집계는 끝난 뒤 결과에 담김")
    stage: Literal["EXTRACTING", "NORMALIZING", "REVIEWING", "PUBLISHING"] = Field(
        description="지금 진행 단계. 할 일 추출 → 담당자·기한 정리와 초안 작성 → 검수(보완 포함) → 발행")


class RunExecutionError(CamelModel):
    task_id: str = Field(description="실행 중 예기치 않은 오류가 난 작업 ID")
    status: Literal["FAILED"]
    error_code: Literal["INTERNAL_ERROR"]
    error_message: str = Field(description="실행 예외 메시지. 이 예외 경로에는 stage·summary·items가 없음")


class DetailError(CamelModel):
    detail: str = Field(description="FastAPI HTTP 오류 메시지. Spring의 code/message와 다름")


class AgentHealth(CamelModel):
    # health는 기존 응답의 snake_case를 유지한다.
    model_config = {"alias_generator": None}
    status: Literal["ok"]
    mcp_server_url: str
    backend_reporting: bool
    dry_run: bool
    model: str


END_EXAMPLE = {
    "taskId": REQUEST_EXAMPLE["taskId"], "status": "SUCCEEDED", "stage": "DONE",
    "errorCode": None, "errorMessage": None,
    "summary": {"total": 0, "approved": 0, "rejected": 0, "needsCheck": 0, "published": 0, "publishFailed": 0, "publishUnknown": 0},
    "items": [],
}
RUN_RESPONSES = {
    200: {"model": RunResult | RunExecutionError, "description": "wait=true인 경우 최종 실행 결과. 작업 실패도 HTTP 200",
          "content": {"application/json": {"example": END_EXAMPLE}}},
    202: {"model": RunAccepted, "description": "비동기 접수 (기본 wait=false). 이미 발행까지 끝난 taskId면 다시 실행하지 않고 기존 결과를 GET으로 제공",
          "content": {"application/json": {"example": {"taskId": REQUEST_EXAMPLE["taskId"], "status": "ACCEPTED"}}}},
    409: {"model": DetailError, "description": "같은 taskId가 현재 RUNNING",
          "content": {"application/json": {"example": {"detail": "이미 처리 중인 task_id입니다"}}}},
}
JOB_RESPONSES = {
    200: {"model": RunProgress | RunResult | RunExecutionError,
          "description": "진행 중 또는 종료 결과. API 요청 오류와 실행 실패를 구분한다.",
          "content": {"application/json": {"examples": {
              "running": {"summary": "실행 중", "value": {"taskId": REQUEST_EXAMPLE["taskId"], "status": "RUNNING", "stage": "REVIEWING"}},
              "finished": {"summary": "할 일 없음으로 종료", "value": END_EXAMPLE},
              "failed": {"summary": "예기치 않은 실행 오류", "value": {"taskId": REQUEST_EXAMPLE["taskId"], "status": "FAILED", "errorCode": "INTERNAL_ERROR", "errorMessage": "실행 처리 실패"}},
          }}}},
    404: {"model": DetailError, "description": "이 프로세스에 작업 없음. 재시작 시에도 발생 가능",
          "content": {"application/json": {"example": {"detail": "없는 task_id입니다"}}}},
}
HEALTH_RESPONSES = {
    200: {"model": AgentHealth, "description": "현재 설정. LLM·MCP 연결 성공을 보장하지 않음",
          "content": {"application/json": {"example": {"status": "ok", "mcp_server_url": "http://127.0.0.1:8000/mcp", "backend_reporting": False, "dry_run": True, "model": "gpt-5.4-nano"}}}},
}


class RunHistoryStep(CamelModel):
    step: int = Field(description="LangGraph 실행 단계 번호")
    node: str = Field(description="이 상태를 만든 노드 (start, prepare, draft, review, publish, finish)")
    next: list[str] = Field(description="다음에 실행할 노드. 끝나면 빈 목록")
    stage: str | None = Field(description="그 시점의 진행 단계")
    tickets: int = Field(description="티켓 수")
    review_status: dict[str, int] = Field(description="검수 상태별 티켓 수 (PENDING, REVISE, APPROVED, NEEDS_CHECK, REJECTED)")
    published: int = Field(description="발행 성공 수")
    error_code: str | None = Field(description="그 시점의 작업 오류 코드")
    created_at: str = Field(description="저장 시각 (UTC, ISO 8601)")


class RunHistory(CamelModel):
    task_id: str = Field(description="작업 ID")
    steps: list[RunHistoryStep] = Field(description="시간 순서의 노드별 기록")


HISTORY_EXAMPLE = {
    "taskId": REQUEST_EXAMPLE["taskId"],
    "steps": [
        {"step": 1, "node": "prepare", "next": ["draft"], "stage": "NORMALIZING", "tickets": 2,
         "reviewStatus": {"PENDING": 2}, "published": 0, "errorCode": None, "createdAt": "2026-10-21T04:10:01.120000+00:00"},
        {"step": 3, "node": "review", "next": ["publish"], "stage": "REVIEWING", "tickets": 2,
         "reviewStatus": {"APPROVED": 2}, "published": 0, "errorCode": None, "createdAt": "2026-10-21T04:10:09.480000+00:00"},
        {"step": 5, "node": "finish", "next": [], "stage": "DONE", "tickets": 2,
         "reviewStatus": {"APPROVED": 2}, "published": 2, "errorCode": None, "createdAt": "2026-10-21T04:10:12.020000+00:00"},
    ],
}
HISTORY_RESPONSES = {
    200: {"model": RunHistory, "description": "노드별 실행 기록. 예시는 일부 단계만 보여 준다",
          "content": {"application/json": {"example": HISTORY_EXAMPLE}}},
    404: {"model": DetailError, "description": "이 프로세스에 실행 기록 없음. 재시작 시에도 발생 가능",
          "content": {"application/json": {"example": {"detail": "없는 task_id입니다"}}}},
}
