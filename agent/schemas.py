"""API 입출력과 LLM 구조화 출력 스키마."""

from __future__ import annotations

import datetime as dt
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

# ---------------------------------------------------------------- API 입력 (백엔드 → Agent, camelCase)


class CamelModel(BaseModel):
    """JSON은 camelCase(taskId), 파이썬 코드는 snake_case(task_id)로 쓴다."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class ProjectMember(CamelModel):
    project_member_id: int | None = Field(None, description="프로젝트 팀원 ID. 미지정 시 null")
    user_id: int | None = Field(None, description="서비스 사용자 ID. 미가입이면 null")
    name: str = Field(description="프로젝트 또는 팀원 이름")
    github_name: str | None = Field(None, description="실제 GitHub 로그인명. 미연결은 null")


class Project(CamelModel):
    project_id: int | None = Field(None, description="프로젝트 ID. 미지정 시 null")
    name: str = Field("", description="프로젝트 또는 팀원 이름")
    github_owner: str = Field(description="GitHub 소유자 또는 조직")
    github_repo: str = Field(description="GitHub 저장소명")
    notion_page_url: str | None = Field(None, description="맥락으로 읽을 Notion 기획 페이지 URL. 없으면 Notion 맥락 없이 진행")
    slack_channel_id: str | None = Field(None, description="프로젝트 Slack 공지 채널 ID")


class Meeting(CamelModel):
    meeting_id: int | str = Field(description="회의 ID. 현재 Agent는 정수·문자열을 허용하며 Spring은 정수를 보냄")
    title: str = Field("", description="회의 또는 업무 제목")
    meeting_date: dt.date | None = Field(None, description="상대 날짜 계산 기준 회의 날짜. 실행 날짜가 아님")
    timezone: str = Field("Asia/Seoul", description="회의 시간대")
    participant_ids: list[int] = Field(default_factory=list, description="참석한 ProjectMember ID 목록")
    original_filename: str = Field("", description="원본 TXT 파일명")
    original_content: str = Field(description="저장된 회의 본문 그대로. 줄바꿈·근거 보존")


class RunRequest(CamelModel):
    task_id: str = Field(description="Spring 발급 작업 ID. Agent는 문자열을 허용")
    meeting: Meeting = Field(description="저장된 회의 정보")
    project: Project = Field(description="대상 저장소·프로젝트")
    project_members: list[ProjectMember] = Field(default_factory=list, description="프로젝트 전체 팀원. 참석자와 동일하지 않을 수 있음")

class CheckMembersRequest(CamelModel):
    github_owner: str = Field(description="확인할 저장소 소유자")
    github_repo: str = Field(description="확인할 저장소 이름")
    github_names: list[str] = Field(description="권한을 확인할 멤버의 GitHub 계정. 계정이 없는 멤버는 보내지 않는다")


class MemberPermissionOut(CamelModel):
    github_name: str
    permission: Literal["GRANTED", "DENIED", "ERROR"] = Field(
        description="GRANTED: 이슈 담당자로 지정 가능, DENIED: 불가, ERROR: 이 멤버만 확인 실패")


class CheckMembersResponse(CamelModel):
    members: list[MemberPermissionOut]


# ---------------------------------------------------------------- 일괄 발행 API (TASK-034)

ProcessingType = Literal["CREATE", "LINK", "UPDATE"]
PublishTarget = Literal["GITHUB", "SLACK", "NOTION"]


class PublishTicket(CamelModel):
    """백엔드가 확인(CONFIRMED) 상태인 티켓만 골라 Agent에 넘기는 단위."""

    ticket_id: str = Field(description="백엔드 task_items의 UUID. 결과에서 그대로 돌려준다")
    project: Project = Field(description="티켓이 속한 프로젝트. 한 일괄 요청에는 여러 프로젝트가 섞일 수 있다")
    meeting_id: int | str = Field(description="이슈 본문과 연결·갱신 문안에 남길 회의 ID")
    meeting_title: str = Field("", description="연결·갱신 문안에 남길 회의 제목")
    meeting_date: dt.date | None = Field(None, description="연결·갱신 문안의 회의 날짜")
    title: str = Field(description="담당자가 확정한 GitHub 이슈 제목")
    content_json: dict[str, Any] = Field(description="담당자가 수정한 description, tasks, due, assignees, 근거 등의 티켓 내용")
    processing_type: ProcessingType = Field(description="확정 처리 유형: CREATE(신규), LINK(댓글 연결), UPDATE(본문 끝 갱신)")
    target_issue_number: int | None = Field(None, ge=1, description="LINK/UPDATE 대상 기존 이슈 번호")
    processing_type_edited: bool = Field(False, description="담당자가 처리 유형을 직접 바꿨으면 재대조가 덮어쓰지 않는다")
    publish_target: PublishTarget = Field("GITHUB", description="GitHub 이슈 또는 회의 결과 Slack 공지")
    slack_members: list[dict[str, str]] = Field(default_factory=list, description="name과 slack_user_id가 있는 연결 완료 Slack 사용자. 공지 멘션에만 쓴다")
    review_status: Literal["CONFIRMED"] = Field(description="백엔드는 CONFIRMED 티켓만 보낸다")


class PublishBatchRequest(CamelModel):
    batch_id: str = Field(description="백엔드가 발급한 발행 묶음 ID. 같은 ID는 중복 발행하지 않는다")
    tickets: list[PublishTicket] = Field(min_length=1, description="사용자가 확인한 티켓 목록")


class PublishItemResult(CamelModel):
    ticket_id: str
    processing_type: ProcessingType = Field(description="발행 직전 재대조 뒤 실제 실행한 유형")
    status: Literal["PUBLISHED", "LINKED", "UPDATED", "POSTED", "FAILED", "UNKNOWN"]
    github_issue_number: int | None = None
    github_issue_url: str | None = None
    comment_url: str | None = Field(None, description="LINK 댓글 또는 UPDATE 알림 댓글 URL")
    message_url: str | None = Field(None, description="Slack 공지 메시지 링크")
    dropped_labels: list[str] = Field(default_factory=list)
    error_code: str | None = None
    error_message: str | None = None
    retryable: bool = False


class PublishBatchResult(CamelModel):
    batch_id: str
    status: Literal["SUCCEEDED", "PARTIAL_FAILED", "FAILED"]
    summary: dict[str, int]
    items: list[PublishItemResult]


# ---------------------------------------------------------------- API 출력 (TASK_ITEMS)

Action = Literal["CREATE", "UPDATE", "DUPLICATE", "NEEDS_CHECK"]
ReviewStatus = Literal["PENDING", "APPROVED", "REJECTED", "NEEDS_CHECK"]
PublishStatus = Literal["NOT_STARTED", "DRAFT", "SUCCEEDED", "FAILED", "SKIPPED", "BLOCKED", "UNKNOWN"]


class TaskItemOut(CamelModel):
    task_item_id: str = Field(description="항목 ID. Spring 공개 응답에서는 itemId로 변환")
    work_key: str | None = Field(description="원문 업무 추적 키. GitHub 이슈 번호가 아님")
    title: str = Field(description="회의 또는 업무 제목")
    content_json: dict[str, Any] = Field(description="업무 내용·담당자·기한·검수·발행 상세. 필드 구성은 Agent 결과를 따름")
    action: Action = Field(description="업무 동작 유형. 현재 실제 발행 경로는 신규 생성")
    reason: str | None = Field(None, description="항목 판단 사유")
    review_status: ReviewStatus = Field(description="검수 결과. 초안 저장 모드(기본)는 PENDING(담당자 확인 대기). PUBLISH_ON_RUN이면 Spring은 APPROVED 외 상태를 REJECTED/BLOCKED로 정규화")
    publish_status: PublishStatus = Field(description="항목별 발행 결과. 초안 저장 모드(기본)는 DRAFT(발행 전 초안). PUBLISH_ON_RUN이고 dry-run이면 승인 항목도 SKIPPED(발행 생략)")
    github_issue_number: int | None = Field(None, description="실제 GitHub 이슈 번호. 없으면 null")
    github_issue_url: str | None = Field(None, description="실제 GitHub 이슈 URL. 없으면 null")
    error_message: str | None = Field(None, description="실행 또는 항목 오류 사유")


class RunResult(CamelModel):
    task_id: str = Field(description="Spring 발급 작업 ID. Agent는 문자열을 허용")
    status: Literal["SUCCEEDED", "FAILED"] = Field(description="Agent 실행 상태. SUCCEEDED가 전체 이슈 생성 성공을 뜻하지 않음")
    stage: str = Field(description="Agent 단계. 종료 시 DONE. Spring 종료 단계는 VERIFYING으로 변환")
    error_code: str | None = Field(None, description="전체 실행 오류 코드. 없으면 null")
    error_message: str | None = Field(None, description="실행 또는 항목 오류 사유")
    summary: dict[str, int] = Field(default_factory=dict, description="Agent 집계. total/drafts/approved/rejected/needsCheck/published/publishFailed/publishUnknown/linked(published 중 기존 이슈 연결). Spring은 항목에서 집계를 다시 계산")
    items: list[TaskItemOut] = Field(default_factory=list, description="최종 항목 목록")
    trace_id: str | None = Field(None, description="LangSmith 실행 ID (Run ID). 동일한 값으로 추적(Trace)을 조회")
    metrics: dict[str, Any] = Field(default_factory=dict, description="실행 지표. 소요 시간, 프롬프트 버전, 모델명, LLM 호출 및 재시도 횟수 포함")


# ---------------------------------------------------------------- 회의록 추출 결과 (REQ-008)
# 표 파서와 LLM 추출 모두 이 형태로 모은다 (extraction.py). 이후 초안 작성·담당자별 저장의 입력이다.


class ExtractedTodo(BaseModel):
    work_key: str = Field(description="원문 추적 키. 액션 아이템 표의 ID, 없으면 '<회의 ID>-auto<n>'")
    action: str = Field(description="할 일 한 문장")
    assignees: list[str] = Field(default_factory=list, description="담당자 이름 원문. 여러 명이면 여러 개, 없으면 빈 목록")
    due: str = Field("", description="기한 원문 표현. 날짜 계산은 코드가 한다(resolve.parse_due). 없으면 빈 문자열")
    evidence_quotes: list[str] = Field(default_factory=list, description="근거가 되는 회의록 원문 문장 1~3개. 원문에 없는 문장은 코드가 뺀다")
    expected: str = Field("", description="예상 티켓 처리 원문 (액션 아이템 표에만 있음). 없으면 빈 문자열")
    warnings: list[str] = Field(default_factory=list, description="코드가 붙인 확인 메모 (원문과 다른 기한·근거 등)")


class ExtractedDecision(BaseModel):
    content: str = Field(description="회의에서 결정된 사항 한 문장")
    evidence_quotes: list[str] = Field(default_factory=list, description="근거가 되는 회의록 원문 문장 1~3개")


class Extraction(BaseModel):
    source: Literal["table", "llm"] = Field(description="table: 액션 아이템 표에서 읽음, llm: 표가 없어 LLM이 뽑음")
    todos: list[ExtractedTodo] = Field(default_factory=list, description="할 일")
    decisions: list[ExtractedDecision] = Field(default_factory=list, description="결정 사항")
    open_questions: list[str] = Field(default_factory=list, description="미결 질문 원문")
    excluded: list[str] = Field(default_factory=list, description="이번 범위에서 제외하거나 보류한 항목 원문")


# ---------------------------------------------------------------- LLM 구조화 출력


class ActionRowOut(BaseModel):
    """액션 아이템 표가 없을 때 LLM이 대신 뽑는 할 일."""

    action: str = Field(description="할 일 한 문장")
    assignees: str = Field(description="담당자 이름. 여러 명이면 쉼표로 구분. 없으면 빈 문자열")
    due: str = Field(description="기한 원문 표현. 없으면 빈 문자열")
    evidence_quotes: list[str] = Field(default_factory=list, description="이 할 일의 근거가 되는 회의록 원문 문장을 글자 그대로 1~3개")


class DecisionOut(BaseModel):
    content: str = Field(description="회의에서 결정된 사항 한 문장")
    evidence_quotes: list[str] = Field(default_factory=list, description="근거가 되는 회의록 원문 문장을 글자 그대로 1~3개")


class ActionRowsOut(BaseModel):
    """표가 없는 회의록의 LLM 추출 결과. extraction.from_llm이 schemas.Extraction으로 바꾼다."""

    rows: list[ActionRowOut] = Field(description="하기로 정한 일")
    decisions: list[DecisionOut] = Field(default_factory=list, description="결정 사항")
    open_questions: list[str] = Field(default_factory=list, description="정해지지 않아 남은 질문. 회의록 문구를 글자 그대로")
    excluded: list[str] = Field(default_factory=list, description="이번 범위에서 제외하거나 보류하기로 한 일. 회의록 문구를 글자 그대로")


class DraftTicket(BaseModel):
    work_key: str = Field(description="입력으로 받은 work_key를 그대로")
    title: str = Field(description="이슈 제목. 한국어, 무엇을 하는지 드러나는 명사형·동사형, 60자 이내")
    description: str = Field(description="할 일 설명 2~4문장. 회의 요약에 있는 내용만")
    tasks: list[str] = Field(description="상세 작업 체크리스트 1~5개. 회의 요약에 있는 범위 안의 작업 단계")
    acceptance_criteria: list[str] = Field(description="확인 가능한 완료 조건 1~5개")
    evidence_quotes: list[str] = Field(description="근거가 되는 회의 요약 원문 문장을 글자 그대로 1~3개")
    open_questions: list[str] = Field(description="이 할 일과 관련된 미결 질문 원문. 없으면 빈 목록")
    labels: list[str] = Field(description="라벨 후보 중에서만 고른 0~3개. 후보가 없거나 맞는 라벨이 없으면 빈 목록")
    publish_target: Literal["GITHUB", "NOTION"] = Field(
        "GITHUB", description="발행 대상. 코드 변경이 한 부분이라도 필요하면 GITHUB, 코드 변경이 필요 없으면 NOTION")
    target_reason: str = Field("", description="발행 대상을 고른 한 줄 사유")


class DraftBatch(BaseModel):
    tickets: list[DraftTicket]


class ReviewCheck(BaseModel):
    item: int = Field(description="점검 항목 번호 (2, 3, 4, 7, 8)")
    passed: bool = Field(description="문제가 없으면 true. 확실한 문제가 있을 때만 false")
    quote: str = Field(description="false일 때 초안에서 문제가 되는 문구를 글자 그대로 복사. true면 빈 문자열")
    problem: str = Field(description="false일 때 무엇이 문제인지 한 문장. true면 빈 문자열")
    fix: str = Field(description="false일 때 어떻게 고칠지 한 문장. true면 빈 문자열")


class TicketReview(BaseModel):
    work_key: str
    checks: list[ReviewCheck] = Field(description="2, 3, 4, 7, 8번 항목 각각의 판정 (5개)")


class ReviewBatch(BaseModel):
    reviews: list[TicketReview]
