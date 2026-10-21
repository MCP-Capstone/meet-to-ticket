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

# ---------------------------------------------------------------- API 출력 (TASK_ITEMS)

Action = Literal["CREATE", "UPDATE", "DUPLICATE", "NEEDS_CHECK"]
ReviewStatus = Literal["PENDING", "APPROVED", "REJECTED", "NEEDS_CHECK"]
PublishStatus = Literal["NOT_STARTED", "SUCCEEDED", "FAILED", "SKIPPED", "BLOCKED", "UNKNOWN"]


class TaskItemOut(CamelModel):
    task_item_id: str = Field(description="항목 ID. Spring 공개 응답에서는 itemId로 변환")
    work_key: str | None = Field(description="원문 업무 추적 키. GitHub 이슈 번호가 아님")
    title: str = Field(description="회의 또는 업무 제목")
    content_json: dict[str, Any] = Field(description="업무 내용·담당자·기한·검수·발행 상세. 필드 구성은 Agent 결과를 따름")
    action: Action = Field(description="업무 동작 유형. 현재 실제 발행 경로는 신규 생성")
    reason: str | None = Field(None, description="항목 판단 사유")
    review_status: ReviewStatus = Field(description="검수 결과. Spring은 APPROVED 외 상태를 REJECTED/BLOCKED로 정규화")
    publish_status: PublishStatus = Field(description="항목별 발행 결과. dry-run이면 승인 항목도 SKIPPED(발행 생략)")
    github_issue_number: int | None = Field(None, description="실제 GitHub 이슈 번호. 없으면 null")
    github_issue_url: str | None = Field(None, description="실제 GitHub 이슈 URL. 없으면 null")
    error_message: str | None = Field(None, description="실행 또는 항목 오류 사유")


class RunResult(CamelModel):
    task_id: str = Field(description="Spring 발급 작업 ID. Agent는 문자열을 허용")
    status: Literal["SUCCEEDED", "FAILED"] = Field(description="Agent 실행 상태. SUCCEEDED가 전체 이슈 생성 성공을 뜻하지 않음")
    stage: str = Field(description="Agent 단계. 종료 시 DONE. Spring 종료 단계는 VERIFYING으로 변환")
    error_code: str | None = Field(None, description="전체 실행 오류 코드. 없으면 null")
    error_message: str | None = Field(None, description="실행 또는 항목 오류 사유")
    summary: dict[str, int] = Field(default_factory=dict, description="Agent 집계. total/approved/rejected/needsCheck/published/publishFailed/publishUnknown/linked(published 중 기존 이슈 연결). Spring은 항목에서 집계를 다시 계산")
    items: list[TaskItemOut] = Field(default_factory=list, description="최종 항목 목록")
    trace_id: str | None = Field(None, description="LangSmith 실행 ID (Run ID). 동일한 값으로 추적(Trace)을 조회")
    metrics: dict[str, Any] = Field(default_factory=dict, description="실행 지표. 소요 시간, 프롬프트 버전, 모델명, LLM 호출 및 재시도 횟수 포함")


# ---------------------------------------------------------------- LLM 구조화 출력


class ActionRowOut(BaseModel):
    """액션 아이템 표가 없을 때 LLM이 대신 뽑는 행."""

    action: str = Field(description="할 일 한 문장")
    assignees: str = Field(description="담당자 이름. 여러 명이면 쉼표로 구분. 없으면 빈 문자열")
    due: str = Field(description="기한 원문 표현. 없으면 빈 문자열")


class ActionRowsOut(BaseModel):
    rows: list[ActionRowOut]


class DraftTicket(BaseModel):
    work_key: str = Field(description="입력으로 받은 work_key를 그대로")
    title: str = Field(description="이슈 제목. 한국어, 무엇을 하는지 드러나는 명사형·동사형, 60자 이내")
    description: str = Field(description="할 일 설명 2~4문장. 회의 요약에 있는 내용만")
    acceptance_criteria: list[str] = Field(description="확인 가능한 완료 조건 1~5개")
    evidence_quotes: list[str] = Field(description="근거가 되는 회의 요약 원문 문장을 글자 그대로 1~3개")
    open_questions: list[str] = Field(description="이 할 일과 관련된 미결 질문 원문. 없으면 빈 목록")


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
