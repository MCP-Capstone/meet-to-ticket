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
    project_member_id: int | None = None
    user_id: int | None = None
    name: str
    github_name: str | None = None



class Project(CamelModel):
    project_id: int | None = None
    name: str = ""
    github_owner: str
    github_repo: str


class Meeting(CamelModel):
    meeting_id: int | str
    title: str = ""
    meeting_date: dt.date | None = None
    timezone: str = "Asia/Seoul"
    participant_ids: list[int] = Field(default_factory=list)
    original_filename: str = ""
    original_content: str


class RunRequest(CamelModel):
    task_id: str
    meeting: Meeting
    project: Project
    project_members: list[ProjectMember] = Field(default_factory=list)


# ---------------------------------------------------------------- API 출력 (TASK_ITEMS)

Action = Literal["CREATE", "UPDATE", "DUPLICATE", "NEEDS_CHECK"]
ReviewStatus = Literal["PENDING", "APPROVED", "REJECTED", "NEEDS_CHECK"]
PublishStatus = Literal["NOT_STARTED", "SUCCEEDED", "FAILED", "SKIPPED", "BLOCKED", "UNKNOWN"]


class TaskItemOut(CamelModel):
    task_item_id: str
    work_key: str | None
    title: str
    content_json: dict[str, Any]
    action: Action
    reason: str | None = None
    review_status: ReviewStatus
    publish_status: PublishStatus
    github_issue_number: int | None = None
    github_issue_url: str | None = None
    error_message: str | None = None


class RunResult(CamelModel):
    task_id: str
    status: Literal["SUCCEEDED", "FAILED"]
    stage: str
    error_code: str | None = None
    error_message: str | None = None
    summary: dict[str, int] = Field(default_factory=dict)
    items: list[TaskItemOut] = Field(default_factory=list)
    trace_id: str | None = None  # LangSmith run id. 같은 값으로 trace를 찾는다
    metrics: dict[str, Any] = Field(default_factory=dict)  # 소요 시간, 프롬프트 버전, 모델, LLM 호출·재시도 수


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
