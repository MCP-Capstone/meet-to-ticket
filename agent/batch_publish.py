"""확인된 티켓을 처리 유형에 따라 GitHub에 일괄 발행한다 (TASK-034)."""

from __future__ import annotations

from collections import Counter
from typing import Any

from handling import change_note, change_section, link_comment
from publisher import ToolCallError, build_issue_body
from schemas import PublishBatchRequest, PublishBatchResult, PublishItemResult, PublishTicket

_RETRYABLE = {"GITHUB_RATE_LIMIT", "GITHUB_ERROR", "MCP_UNAVAILABLE", "PUBLISH_FAILED"}


def _ticket(source: PublishTicket) -> dict[str, Any]:
    """저장된 contentJson을 기존 본문·연결 문안 함수가 쓰는 내부 티켓 모양으로 바꾼다."""
    content = dict(source.content_json)
    content.update({
        "title": source.title,
        "work_key": content.get("work_key") or source.ticket_id,
        "action_text": content.get("action_text") or content.get("description") or source.title,
        "handling": {
            "type": source.processing_type,
            "issue_number": source.target_issue_number,
            "edited": source.processing_type_edited,
        },
    })
    return content


def _failure(ticket_id: str, processing_type: str, error: ToolCallError) -> PublishItemResult:
    return PublishItemResult(
        ticket_id=ticket_id, processing_type=processing_type, status="UNKNOWN" if error.ambiguous else "FAILED",
        error_code=error.code, error_message=error.message[:1000], retryable=error.ambiguous or error.code in _RETRYABLE,
    )


async def publish_one(source: PublishTicket, publisher: Any) -> PublishItemResult:
    """이미 재대조가 반영된 티켓 하나를 CREATE/LINK/UPDATE로 실행한다."""
    ticket = _ticket(source)
    handling = ticket["handling"]
    kind = handling["type"]
    number = handling.get("issue_number")
    meeting_date = source.meeting_date.isoformat() if source.meeting_date else ""
    try:
        if kind == "LINK":
            if not number:
                raise ToolCallError("INVALID_ARGUMENT", "LINK 처리에는 target_issue_number가 필요합니다")
            out = await publisher.add_comment(
                owner=source.project.github_owner, repo=source.project.github_repo, number=number,
                body=link_comment(ticket, meeting_date=meeting_date, meeting_title=source.meeting_title),
            )
            return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="LINKED",
                                     github_issue_number=number,
                                     github_issue_url=f"https://github.com/{source.project.github_owner}/{source.project.github_repo}/issues/{number}",
                                     comment_url=out.get("url"))
        if kind == "UPDATE":
            if not number:
                raise ToolCallError("INVALID_ARGUMENT", "UPDATE 처리에는 target_issue_number가 필요합니다")
            out = await publisher.update_issue(
                owner=source.project.github_owner, repo=source.project.github_repo, number=number,
                append_section=change_section(ticket, meeting_date=meeting_date, meeting_title=source.meeting_title),
                add_labels=ticket.get("labels") or [], note=change_note(meeting_date=meeting_date, meeting_title=source.meeting_title),
            )
            if out.get("comment_error"):
                # 본문 갱신은 성공했으므로 실패로 바꾸지 않는다. 백엔드에는 재시도할 수 있게 오류를 남긴다.
                return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="UPDATED",
                                         github_issue_number=out.get("number", number), github_issue_url=out.get("url"),
                                         dropped_labels=out.get("dropped_labels") or [], error_code="COMMENT_FAILED",
                                         error_message=out["comment_error"][:1000], retryable=True)
            return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="UPDATED",
                                     github_issue_number=out.get("number", number), github_issue_url=out.get("url"),
                                     dropped_labels=out.get("dropped_labels") or [])
        out = await publisher.create_issue(
            owner=source.project.github_owner, repo=source.project.github_repo, title=source.title,
            body=build_issue_body(ticket, str(source.meeting_id), ticket.get("background", "")),
            labels=ticket.get("labels") or [], assignees=ticket.get("assignees") or [],
        )
        return PublishItemResult(ticket_id=source.ticket_id, processing_type="CREATE", status="PUBLISHED",
                                 github_issue_number=out.get("number"), github_issue_url=out.get("url"),
                                 dropped_labels=out.get("dropped_labels") or [])
    except ToolCallError as exc:
        return _failure(source.ticket_id, kind, exc)


async def publish_batch(request: PublishBatchRequest, publisher: Any) -> PublishBatchResult:
    """프로젝트 실패가 다른 프로젝트 티켓을 막지 않도록 항목 단위로 끝까지 실행한다."""
    items = [await publish_one(ticket, publisher) for ticket in request.tickets]
    counts = Counter(item.status for item in items)
    status = "FAILED" if counts and counts["FAILED"] + counts["UNKNOWN"] == len(items) else (
        "PARTIAL_FAILED" if counts["FAILED"] or counts["UNKNOWN"] else "SUCCEEDED"
    )
    return PublishBatchResult(batch_id=request.batch_id, status=status, summary=dict(counts), items=items)
