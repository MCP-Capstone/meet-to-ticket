"""현재 /runs API의 OpenAPI 메타데이터. 실행·인증 동작은 변경하지 않는다."""

from typing import Literal

from pydantic import Field

from schemas import CamelModel, RunResult

DESCRIPTION = """
Spring이 호출하는 Agent API. Spring의 공개 API와는 다른 서버에서 제공한다.

- POST /runs는 기본적으로 202 ACCEPTED를 반환하고 백그라운드에서 처리한다.
- GET /runs/{task_id}로 RUNNING 또는 종료 결과 SUCCEEDED / FAILED를 조회한다.
- SUCCEEDED는 Agent 실행 완료이며, 모든 GitHub 발행 성공을 뜻하지 않는다. items와 summary를 함께 확인한다.
- wait=true는 로컬 테스트용 동기 실행이며 HTTP 200으로 최종 결과를 반환한다.
- 생성·조회 상태는 메모리에 있어 프로세스 재시작 시 사라진다.
- 같은 taskId의 RUNNING 작업만 409로 차단한다. 종료된 taskId 재접수는 다시 실행되므로 반복 POST하지 않는다.
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
    status: Literal["RUNNING"] = Field(description="현재 응답은 세부 단계·항목을 포함하지 않음")


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
    202: {"model": RunAccepted, "description": "비동기 접수 (기본 wait=false)",
          "content": {"application/json": {"example": {"taskId": REQUEST_EXAMPLE["taskId"], "status": "ACCEPTED"}}}},
    409: {"model": DetailError, "description": "같은 taskId가 현재 RUNNING",
          "content": {"application/json": {"example": {"detail": "이미 처리 중인 task_id입니다"}}}},
}
JOB_RESPONSES = {
    200: {"model": RunProgress | RunResult | RunExecutionError,
          "description": "진행 중 또는 종료 결과. API 요청 오류와 실행 실패를 구분한다.",
          "content": {"application/json": {"examples": {
              "running": {"summary": "실행 중", "value": {"taskId": REQUEST_EXAMPLE["taskId"], "status": "RUNNING"}},
              "finished": {"summary": "할 일 없음으로 종료", "value": END_EXAMPLE},
              "failed": {"summary": "예기치 않은 실행 오류", "value": {"taskId": REQUEST_EXAMPLE["taskId"], "status": "FAILED", "errorCode": "INTERNAL_ERROR", "errorMessage": "실행 처리 실패"}},
          }}}},
    404: {"model": DetailError, "description": "이 프로세스에 작업 없음. 재시작 시에도 발생 가능",
          "content": {"application/json": {"example": {"detail": "없는 task_id입니다"}}}},
}
HEALTH_RESPONSES = {
    200: {"model": AgentHealth, "description": "현재 설정. LLM·MCP 연결 성공을 보장하지 않음",
          "content": {"application/json": {"example": {"status": "ok", "mcp_server_url": "http://127.0.0.1:8000/mcp", "backend_reporting": False, "dry_run": True, "model": "gpt-4o-mini"}}}},
}
