"""확인된 티켓을 발행 대상(GitHub 이슈, Notion 페이지, Slack 공지)에 따라 일괄 발행한다 (TASK-034)."""

from __future__ import annotations

import logging
from collections import Counter
from typing import Any

from handling import change_note, change_section, link_comment
from publisher import ToolCallError, build_issue_body, build_notion_page
from schemas import PublishBatchRequest, PublishBatchResult, PublishItemResult, PublishTicket
from state import PUBLISH_FAILED

log = logging.getLogger("agent.batch_publish")

# 이미 반영됐는지 찾아볼 도구가 없는 비멱등 대상(create_page, post_message). 결과를 모르는 재시도는 중복 페이지나 공지를 만들 수 있어 보내지 않는다.
# 담당자가 대상 도구에서 확인한 결과를 남기면(백엔드 resolve-outcome, #280) previous_outcome_unknown이 풀려 다시 보낼 수 있다.
_VERIFY_REQUIRED = {
    "NOTION": ("NOTION_VERIFY_REQUIRED", "이전 Notion 발행 결과를 알 수 없어 중복 페이지를 막기 위해 다시 만들지 않았습니다. Notion의 '회의 후속 티켓' 폴더에서 페이지가 있는지 확인한 뒤 결과를 남기세요"),
    "SLACK": ("SLACK_VERIFY_REQUIRED", "이전 Slack 공지 결과를 알 수 없어 중복 게시를 막기 위해 다시 보내지 않았습니다. Slack 채널에서 공지가 올라갔는지 확인한 뒤 결과를 남기세요"),
}
_RETRYABLE = {"GITHUB_RATE_LIMIT", "GITHUB_ERROR", "MCP_UNAVAILABLE", "PUBLISH_FAILED", "NOTION_RATE_LIMIT", "NOTION_ERROR"}


def _ticket(source: PublishTicket) -> dict[str, Any]:
    """저장된 contentJson을 기존 본문·연결 문안 함수가 쓰는 내부 티켓 모양으로 바꾼다."""
    content = dict(source.content_json)
    assignees = content.get("assignees")
    if isinstance(assignees, dict):
        content.update({
            "assignee_names": assignees.get("names") or [],
            "assignees": assignees.get("github") or [],
            "unresolved_assignees": assignees.get("unresolved") or [],
            "unmatched_assignees": assignees.get("unmatched") or [],
            "ambiguous_assignees": assignees.get("ambiguous") or [],
        })
    content.update({
        "title": source.title,
        "work_key": content.get("work_key") or source.ticket_id,
        "action_text": content.get("action_text") or content.get("description") or source.title,
        "handling": {
            "type": source.processing_type,
            "issue_number": source.target_issue_number,
            # 결과 모름 재시도는 담당자가 유형을 바꿨어도 재대조가 반드시 돌아야 한다
            "edited": source.processing_type_edited and not source.previous_outcome_unknown,
        },
    })
    return content


def marker(kind: str, source: PublishTicket, ticket: dict[str, Any]) -> str:
    """연결 댓글, 변경 섹션에 숨겨 넣는 표식. 결과 모름 재발행에서 이미 반영됐는지 알아내는 데 쓴다."""
    return f"<!-- meettoticket:{kind} meeting={source.meeting_id} work_key={ticket.get('work_key')} -->"


def publication_preview(source: PublishTicket) -> dict[str, Any]:
    """발행과 조회가 공유하는 순수 본문 생성. LLM/MCP/외부 쓰기를 호출하지 않는다."""
    if source.publish_target != "GITHUB":
        raise ValueError("현재 최종 발행 본문 미리보기는 GitHub만 지원합니다")
    ticket = _ticket(source)
    kind = source.processing_type
    date = source.meeting_date.isoformat() if source.meeting_date else ""
    secondary = None
    if kind == "CREATE":
        body = build_issue_body(ticket, str(source.meeting_id), ticket.get("background", ""))
    else:
        if not source.target_issue_number:
            raise ValueError("연결·갱신 대상 이슈 번호가 없습니다")
        if kind == "LINK":
            body = link_comment(ticket, meeting_date=date, meeting_title=source.meeting_title)
        else:
            body = change_section(ticket, meeting_date=date, meeting_title=source.meeting_title)
            secondary = change_note(meeting_date=date, meeting_title=source.meeting_title)
            secondary += "\n\n" + marker("update-comment", source, ticket)
        # 원문은 보존하면서 담당자가 저장한 설명도 실제 댓글/추가 섹션에 반영한다.
        description = (ticket.get("description") or "").strip()
        if description and description != (ticket.get("action_text") or "").strip():
            body += "\n\n## 작업 내용\n" + description
        body += "\n\n" + marker("link" if kind == "LINK" else "update", source, ticket)
    return {"kind": kind, "body": body, "secondaryBody": secondary,
            "labels": (ticket.get("labels") or []) if kind != "LINK" else [],
            "githubAssignees": (ticket.get("assignees") or []) if kind == "CREATE" else [],
            "targetIssueNumber": source.target_issue_number}


async def _already_applied(publisher: Any, source: PublishTicket, number: int, text: str) -> dict[str, Any] | None:
    """표식이 이슈 본문이나 댓글에 이미 있으면 그 위치를 돌려준다. 확인하지 못하면 예외로 막아 중복 쓰기를 피한다."""
    out = await publisher.find_marker(owner=source.project.github_owner, repo=source.project.github_repo,
                                      number=number, marker=text)
    if out.get("found"):
        return out
    if out.get("truncated"):
        raise ToolCallError("MARKER_CHECK_INCOMPLETE", "댓글이 많아 이미 반영됐는지 끝까지 확인하지 못했습니다. GitHub에서 확인하세요")
    return None


async def _comment_already_sent(publisher: Any, source: PublishTicket, number: int,
                                ticket: dict[str, Any]) -> dict[str, Any] | None:
    """갱신 알림 댓글이 이미 있는지 찾는다. 표식으로 먼저 찾고, 없으면 표식 없는 알림 문구로 한 번 더 찾는다.

    #291 이전에 갱신한 티켓은 알림 댓글에 update-comment 표식이 없어 표식만 보면 이미 보낸 댓글을 놓치고 중복으로 보낸다.
    같은 회의의 알림 문구는 work_key와 상관없이 같으므로, 같은 회의의 다른 티켓이 남긴 알림이 있어도 보낸 것으로 본다(같은 문구를 두 번 올리지 않는다).
    """
    found = await _already_applied(publisher, source, number, marker("update-comment", source, ticket))
    if found:
        return found
    date = source.meeting_date.isoformat() if source.meeting_date else ""
    legacy = await _already_applied(publisher, source, number,
                                    change_note(meeting_date=date, meeting_title=source.meeting_title))
    return legacy if legacy and legacy.get("where") == "comment" else None


async def _existing_issue(publisher: Any, source: PublishTicket, ticket: dict[str, Any]) -> dict[str, Any] | None:
    """같은 work_key로 이미 만들어진 열린 이슈를 찾는다. 제목까지 같아야 같은 티켓으로 본다."""
    work_key = ticket.get("work_key")
    out = await publisher.search_issues(owner=source.project.github_owner, repo=source.project.github_repo,
                                        query=f'"{work_key}"', state="open", limit=10)
    for issue in out.get("issues") or []:
        if issue.get("state") == "open" and (issue.get("title") or "").strip() == source.title.strip():
            return issue
    return None


def _block_unverified_retry(source: PublishTicket) -> None:
    """설정 검사를 마친 뒤, 쓰기 직전에 부른다. 확실한 설정 오류가 '확인 필요'에 가려지지 않게 한다."""
    if source.previous_outcome_unknown and source.publish_target in _VERIFY_REQUIRED:
        raise ToolCallError(*_VERIFY_REQUIRED[source.publish_target])


def _failure(ticket_id: str, processing_type: str, error: ToolCallError) -> PublishItemResult:
    return PublishItemResult(
        ticket_id=ticket_id, processing_type=processing_type, status="UNKNOWN" if error.ambiguous else "FAILED",
        error_code=error.code, error_message=error.message[:1000], retryable=error.ambiguous or error.code in _RETRYABLE,
    )


def _skipped(ticket_id: str, processing_type: str) -> PublishItemResult:
    """외부 시스템에 쓰지 않은 항목은 성공처럼 보이지 않게 결과를 분리한다."""
    return PublishItemResult(
        ticket_id=ticket_id, processing_type=processing_type, status="SKIPPED",
        error_message="발행하지 않음: 외부 시스템에 변경을 만들지 않았습니다.",
    )


async def publish_one(source: PublishTicket, publisher: Any) -> PublishItemResult:
    """이미 재대조가 반영된 티켓 하나를 CREATE/LINK/UPDATE로 실행한다."""
    ticket = _ticket(source)
    handling = ticket["handling"]
    # Slack·Notion은 항상 신규 발행이다 (백엔드 REQ-015). 저장된 유형이 달라도 실패 결과까지 CREATE로 보고한다 (#297)
    kind = handling["type"] if source.publish_target == "GITHUB" else "CREATE"
    number = handling.get("issue_number")
    sent = False  # GitHub 쓰기 요청을 보낸 뒤의 예외는 결과를 모르므로 UNKNOWN으로 남긴다
    try:
        if source.retry_comment_only:
            if source.publish_target != "GITHUB" or kind != "UPDATE" or not number:
                raise ToolCallError("INVALID_ARGUMENT", "댓글 재전송은 갱신 완료한 GitHub 이슈만 가능합니다")
            found = await _comment_already_sent(publisher, source, number, ticket)
            out = found or await publisher.add_comment(
                owner=source.project.github_owner, repo=source.project.github_repo, number=number,
                body=publication_preview(source)["secondaryBody"],
            )
            if out.get("status") == "dry_run":
                raise ToolCallError("COMMENT_FAILED", "댓글을 실제 전송하지 않았습니다")
            return PublishItemResult(ticket_id=source.ticket_id, processing_type="UPDATE", status="UPDATED",
                                     github_issue_number=number,
                                     github_issue_url=f"https://github.com/{source.project.github_owner}/{source.project.github_repo}/issues/{number}",
                                     comment_url=out.get("url"))
        if source.publish_target == "SLACK":
            channel_id = source.project.slack_channel_id
            if not channel_id:
                raise ToolCallError("INVALID_ARGUMENT", "Slack 공지에는 프로젝트 slack_channel_id가 필요합니다")
            notice = ticket.get("slack") or {}
            text = (notice.get("text") or ticket.get("description") or "").strip()
            if not text:
                raise ToolCallError("INVALID_ARGUMENT", "Slack 공지 문구가 비어 있습니다")
            reviewer_names = {r.get("name") for r in notice.get("reviewers") or []}
            mentions = [member for member in source.slack_members if member.get("name") in reviewer_names and member.get("slack_user_id")]
            _block_unverified_retry(source)
            sent = True
            out = await publisher.post_message(channel_id=channel_id, text=text, mentions=mentions)
            if out.get("status") == "dry_run":
                return _skipped(source.ticket_id, "CREATE")
            # business-mcp post_message는 메시지 링크를 permalink로 돌려준다 (url 키 없음)
            return PublishItemResult(ticket_id=source.ticket_id, processing_type="CREATE", status="POSTED",
                                     message_url=out.get("permalink"))
        if source.publish_target == "NOTION":
            # 루트 페이지가 없으면 Notion을 부르지 않는다. GitHub 이슈로 대신 만들지도 않는다.
            root = (source.project.notion_page_url or "").strip()
            if not root:
                raise ToolCallError("NOTION_ROOT_MISSING", "프로젝트 설정에 Notion 기획 페이지 URL이 없어 Notion 페이지를 만들 수 없습니다")
            page = build_notion_page(ticket, str(source.meeting_id), ticket.get("background", ""))
            _block_unverified_retry(source)
            sent = True
            out = await publisher.create_page(root_page_url_or_id=root, title=source.title or page["title"],
                                              markdown=page["markdown"])
            if out.get("status") == "dry_run":
                return _skipped(source.ticket_id, "CREATE")
            if not out.get("url"):
                raise ToolCallError(PUBLISH_FAILED, "Notion 페이지는 만들었지만 URL을 받지 못했습니다", ambiguous=True)
            return PublishItemResult(ticket_id=source.ticket_id, processing_type="CREATE", status="PUBLISHED",
                                     notion_page_id=out.get("page_id"), notion_page_url=out.get("url"))
        if kind == "LINK":
            if not number:
                raise ToolCallError("INVALID_ARGUMENT", "LINK 처리에는 target_issue_number가 필요합니다")
            tag = marker("link", source, ticket)
            body = publication_preview(source)["body"]
            if source.previous_outcome_unknown:
                found = await _already_applied(publisher, source, number, tag)
                if found:
                    return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="LINKED",
                                             github_issue_number=number, comment_url=found.get("url"),
                                             github_issue_url=f"https://github.com/{source.project.github_owner}/{source.project.github_repo}/issues/{number}")
            sent = True
            out = await publisher.add_comment(
                owner=source.project.github_owner, repo=source.project.github_repo, number=number, body=body,
            )
            if out.get("status") == "dry_run":
                return _skipped(source.ticket_id, kind)
            return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="LINKED",
                                     github_issue_number=number,
                                     github_issue_url=f"https://github.com/{source.project.github_owner}/{source.project.github_repo}/issues/{number}",
                                     comment_url=out.get("url"))
        if kind == "UPDATE":
            if not number:
                raise ToolCallError("INVALID_ARGUMENT", "UPDATE 처리에는 target_issue_number가 필요합니다")
            tag = marker("update", source, ticket)
            preview = publication_preview(source)
            section = preview["body"]
            if source.previous_outcome_unknown:
                # update_issue는 본문 끝에 덧붙이는 비멱등 쓰기라, 같은 회의의 섹션이 이미 있으면 덧붙이지 않는다
                found = await _already_applied(publisher, source, number, tag)
                if found:
                    return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="UPDATED",
                                             github_issue_number=number,
                                             github_issue_url=f"https://github.com/{source.project.github_owner}/{source.project.github_repo}/issues/{number}")
            note = preview["secondaryBody"]
            sent = True
            out = await publisher.update_issue(
                owner=source.project.github_owner, repo=source.project.github_repo, number=number,
                append_section=section, add_labels=ticket.get("labels") or [], note=note,
            )
            if out.get("status") == "dry_run":
                return _skipped(source.ticket_id, kind)
            if out.get("comment_error"):
                # 본문 갱신은 성공했으므로 실패로 바꾸지 않는다. 백엔드에는 재시도할 수 있게 오류를 남긴다.
                return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="UPDATED",
                                         github_issue_number=out.get("number", number), github_issue_url=out.get("url"),
                                         dropped_labels=out.get("dropped_labels") or [], error_code="COMMENT_FAILED",
                                         error_message=out["comment_error"][:1000], retryable=True)
            return PublishItemResult(ticket_id=source.ticket_id, processing_type=kind, status="UPDATED",
                                     github_issue_number=out.get("number", number), github_issue_url=out.get("url"),
                                     dropped_labels=out.get("dropped_labels") or [])
        if source.previous_outcome_unknown:
            existing = await _existing_issue(publisher, source, ticket)
            if existing:
                return PublishItemResult(ticket_id=source.ticket_id, processing_type="CREATE", status="PUBLISHED",
                                         github_issue_number=existing.get("number"), github_issue_url=existing.get("url"))
        body = publication_preview(source)["body"]
        sent = True
        out = await publisher.create_issue(
            owner=source.project.github_owner, repo=source.project.github_repo, title=source.title, body=body,
            labels=ticket.get("labels") or [], assignees=ticket.get("assignees") or [],
        )
        if out.get("status") == "dry_run":
            return _skipped(source.ticket_id, "CREATE")
        return PublishItemResult(ticket_id=source.ticket_id, processing_type="CREATE", status="PUBLISHED",
                                 github_issue_number=out.get("number"), github_issue_url=out.get("url"),
                                 dropped_labels=out.get("dropped_labels") or [])
    except ToolCallError as exc:
        if source.retry_comment_only:
            if exc.code == "MARKER_CHECK_INCOMPLETE":
                # 이미 보냈는지 끝까지 확인하지 못했다. 다시 보내도 같은 결과라 재전송을 막고 GitHub에서 직접 확인하게 한다.
                return _comment_failure(source, exc.message, code=exc.code, retryable=False)
            return _comment_failure(source, exc.message)
        return _failure(source.ticket_id, kind, exc)
    except Exception as exc:  # noqa: BLE001
        # 한 티켓의 예외가 묶음 전체를 멈추면 앞서 발행된 결과까지 사라져 재요청 때 중복 발행된다.
        log.exception("티켓 발행 중 예상하지 못한 오류 ticket=%s", source.ticket_id)
        if source.retry_comment_only:
            return _comment_failure(source, f"{type(exc).__name__}: {exc}")
        return _failure(source.ticket_id, kind,
                        ToolCallError(PUBLISH_FAILED, f"{type(exc).__name__}: {exc}", ambiguous=sent))


def _comment_failure(source: PublishTicket, message: str, *, code: str = "COMMENT_FAILED",
                     retryable: bool = True) -> PublishItemResult:
    # 본문 갱신 성공은 유지한다. 다음 재전송도 반드시 댓글 표식을 먼저 확인한다.
    return PublishItemResult(ticket_id=source.ticket_id, processing_type="UPDATE", status="UPDATED",
                             github_issue_number=source.target_issue_number,
                             github_issue_url=f"https://github.com/{source.project.github_owner}/{source.project.github_repo}/issues/{source.target_issue_number}",
                             error_code=code, error_message=message[:1000], retryable=retryable)


async def publish_batch(request: PublishBatchRequest, publisher: Any) -> PublishBatchResult:
    """프로젝트 실패가 다른 프로젝트 티켓을 막지 않도록 항목 단위로 끝까지 실행한다."""
    items = [await publish_one(ticket, publisher) for ticket in request.tickets]
    counts = Counter(item.status for item in items)
    status = "FAILED" if counts and counts["FAILED"] + counts["UNKNOWN"] == len(items) else (
        "PARTIAL_FAILED" if counts["FAILED"] or counts["UNKNOWN"] else "SUCCEEDED"
    )
    return PublishBatchResult(batch_id=request.batch_id, status=status, summary=dict(counts), items=items)
