"""그래프 노드.

흐름: prepare(추출 준비·담당자·기한 정리) → draft(초안 작성) → review(검수)
      → (보완 필요 시 draft로, 최대 MAX_REVISIONS회) → publish(발행) → finish(결과 정리·보고)

LLM, 발행 도구, 백엔드 보고는 생성자로 주입한다. 테스트에서는 가짜를 넣는다.
"""

from __future__ import annotations

import logging
import time
import uuid
from datetime import date
from typing import Any

import prompts
from config import Settings
from parsing import ActionRow, parse_summary
from publisher import ToolCallError, build_issue_body
from resolve import classify_expected, map_assignees, normalize_ws, parse_due, split_names
from schemas import ProjectMember, RunResult
from state import (
    CHECK_NAMES,
    FATAL_PUBLISH_CODES,
    INPUT_TOO_LONG,
    TASK_TIMEOUT,
    ADVISORY_ITEMS,
    LLM_ITEMS,
    NO_TICKETS,
    REJECT_ITEMS,
    SCOPE_ITEMS,
    REVIEW_APPROVED,
    REVIEW_NEEDS_CHECK,
    REVIEW_PENDING,
    REVIEW_REJECTED,
    REVIEW_REVISE,
    STAGE_DONE,
    STAGE_EXTRACTING,
    STAGE_NORMALIZING,
    STAGE_PUBLISHING,
    STAGE_REVIEWING,
    STATUS_FAILED,
    STATUS_RUNNING,
    STATUS_SUCCEEDED,
    AgentFatal,
    AgentState,
)

log = logging.getLogger("agent.nodes")


def _chunks(items: list[Any], size: int) -> list[list[Any]]:
    size = max(1, size)
    return [items[i : i + size] for i in range(0, len(items), size)]


def _copy(tickets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [dict(t) for t in tickets]


def run_metrics(settings: Settings, llm: Any, started_at: float | None) -> dict[str, Any]:
    """결과에 담는 실행 지표. 키는 백엔드 응답과 같은 camelCase."""
    from langsmith.utils import tracing_is_enabled

    return {
        "durationMs": int((time.time() - started_at) * 1000) if started_at else None,
        "promptVersion": prompts.PROMPT_VERSION,
        "model": settings.openai_model,
        "reviewModel": settings.review_model,
        "tracing": bool(tracing_is_enabled()),
        **dict(getattr(llm, "stats", {}) or {}),  # 가짜 LLM에는 없다
    }


def new_ticket(row: ActionRow, members: list[ProjectMember], meeting_date: date | None, source: str = "table") -> dict[str, Any]:
    names = split_names(row.assignees_raw)
    assignees = map_assignees(names, members)
    due = parse_due(row.due_raw, meeting_date)
    expected = classify_expected(row.expected_raw)
    return {
        "task_item_id": str(uuid.uuid4()),
        "source": source,  # table: 회의록 액션 아이템 표, llm: 표가 없어 LLM이 뽑음
        "work_key": row.work_key or None,
        "action_text": row.action,
        "assignee_names": assignees.names,
        "assignees": assignees.logins,
        "unresolved_assignees": assignees.unresolved,
        "due": {"text": due.text, "date": due.date, "time": due.time},
        "expected": {"raw": expected.raw, "kind": expected.kind, "keys": expected.keys},
        "title": "",
        "description": "",
        "acceptance_criteria": [],
        "evidence_quotes": [],
        "open_questions": [],
        "review_status": REVIEW_PENDING,
        "failed_items": [],
        "feedback": [],
        "revisions": 0,
        "action": "CREATE",
        "reason": None,
        "publish_status": "NOT_STARTED",
        "github_issue_number": None,
        "github_issue_url": None,
        "error_message": None,
        "warnings": [],
    }


class AgentNodes:
    def __init__(self, llm: Any, publisher: Any, reporter: Any, settings: Settings):
        self.llm = llm
        self.publisher = publisher
        self.reporter = reporter
        self.settings = settings

    async def _progress(self, state: AgentState, stage: str) -> None:
        try:
            await self.reporter.progress(state["task_id"], STATUS_RUNNING, stage)
        except Exception as exc:  # noqa: BLE001 - 보고 실패로 작업을 멈추지 않는다
            log.warning("진행 보고 실패: %s", exc)

    # ------------------------------------------------------------ 1. 추출 준비

    def _past_deadline(self, state: AgentState) -> dict[str, Any] | None:
        """마감이 지났으면 작업을 끝내는 상태 변경을 돌려준다. 초안·검수 노드 시작 시에만 확인한다.

        발행은 시작하면 끝까지 한다. 중간에 끊으면 만들어진 이슈가 결과에서 빠질 수 있기 때문이다.
        """
        deadline_at = state.get("deadline_at")
        if deadline_at is None or time.time() <= deadline_at:
            return None
        limit = self.settings.task_deadline_seconds
        log.warning("작업 마감 초과 task=%s (%s초)", state.get("task_id"), limit)
        return {"error_code": TASK_TIMEOUT, "error_message": f"작업 마감 시간({limit}초)을 넘겨 중단함"}

    async def prepare(self, state: AgentState) -> dict[str, Any]:
        await self._progress(state, STAGE_EXTRACTING)
        limit = self.settings.max_source_chars
        if limit > 0 and len(state["original_content"]) > limit:
            # 너무 긴 회의록은 LLM을 부르지 않고 끝낸다 (Token 예산)
            return {
                "error_code": INPUT_TOO_LONG,
                "error_message": f"회의 원문이 {len(state['original_content'])}자로 한도 {limit}자를 넘음",
                "stage": STAGE_EXTRACTING,
            }
        parsed = parse_summary(state["original_content"])
        meeting_date = (
            date.fromisoformat(state["meeting_date"]) if state.get("meeting_date") else parsed.meeting_date
        )
        rows = parsed.rows
        source = "table"
        if not rows:
            source = "llm"
            try:
                extracted = await self.llm.extract_rows(state["original_content"])
            except AgentFatal as exc:
                return {"error_code": exc.code, "error_message": exc.message, "stage": STAGE_EXTRACTING}
            rows = [
                ActionRow(f"{state['meeting_id']}-auto{i}", r.action, r.assignees, r.due, "")
                for i, r in enumerate(extracted, start=1)
            ]
        if rows:
            await self._progress(state, STAGE_NORMALIZING)
        members = [ProjectMember(**m) for m in state.get("members", [])]
        tickets = [new_ticket(row, members, meeting_date, source) for row in rows]
        if source == "llm":
            src = normalize_ws(state["original_content"])
            for t in tickets:
                text = t["due"]["text"]
                if text and normalize_ws(text) not in src:
                    t["warnings"].append(f"기한 '{text}'이(가) 원문 표현과 달라 확인 필요")
        log.info("추출 준비: 액션 아이템 %d건", len(tickets))
        return {
            "tickets": tickets,
            "background": parsed.background,
            "open_questions": parsed.open_questions,
            "excluded": parsed.excluded,
            "meeting_date": meeting_date.isoformat() if meeting_date else None,
            # 이후 초안 작성은 NORMALIZING 단계로 본다 (초안에서 실패하면 이 단계로 보고)
            "stage": STAGE_NORMALIZING if tickets else STAGE_EXTRACTING,
            "error_code": None if tickets else NO_TICKETS,
            "error_message": None if tickets else "회의 요약에서 할 일을 찾지 못했습니다",
        }

    # ------------------------------------------------------------ 2. 초안 작성 (보완 포함)

    async def draft(self, state: AgentState) -> dict[str, Any]:
        if (stop := self._past_deadline(state)) is not None:
            return stop
        tickets = _copy(state["tickets"])
        targets = [
            t for t in tickets
            if t["review_status"] == REVIEW_REVISE or (t["review_status"] == REVIEW_PENDING and not t["title"])
        ]
        for batch in _chunks(targets, self.settings.draft_batch_size):
            items = []
            for t in batch:
                item: dict[str, Any] = {
                    "work_key": t["work_key"] or t["task_item_id"],
                    "action": t["action_text"],
                    "assignees": t["assignee_names"],
                    "due": t["due"]["text"],
                    "expected_handling": t["expected"]["raw"],
                }
                if t["review_status"] == REVIEW_REVISE:
                    item["current_draft"] = {
                        "title": t["title"],
                        "description": t["description"],
                        "acceptance_criteria": t["acceptance_criteria"],
                        "evidence_quotes": t["evidence_quotes"],
                    }
                    item["feedback"] = t["feedback"]
                items.append(item)
            try:
                drafts = await self.llm.draft(state["original_content"], items)
            except AgentFatal as exc:
                return {"error_code": exc.code, "error_message": exc.message}
            by_key = {d.work_key: d for d in drafts}
            for t in batch:
                d = by_key.get(t["work_key"] or t["task_item_id"])
                if t["review_status"] == REVIEW_REVISE:
                    t["revisions"] += 1
                    t["review_status"] = REVIEW_PENDING
                if d is None:
                    continue
                t["title"] = d.title.strip()
                t["description"] = d.description.strip()
                t["acceptance_criteria"] = [c.strip() for c in d.acceptance_criteria if c.strip()]
                normalized = normalize_ws(state["original_content"])
                quotes = [q.strip() for q in d.evidence_quotes if q.strip()]
                kept = [q for q in quotes if normalize_ws(q) in normalized]
                if len(kept) < len(quotes):
                    t["warnings"].append(f"원문과 다른 근거 인용 {len(quotes) - len(kept)}개를 제외함")
                t["evidence_quotes"] = kept or [t["action_text"]]
                t["open_questions"] = [q.strip() for q in d.open_questions if q.strip()]
        return {"tickets": tickets}

    # ------------------------------------------------------------ 3. 검수

    def _code_checks(self, t: dict[str, Any], meeting_date: str | None) -> list[dict[str, Any]]:
        fails: list[dict[str, Any]] = []
        if not t["work_key"] and not t["action_text"]:
            fails.append({"item": 1, "problem": "근거가 되는 액션 아이템이 없음", "fix": "발행하지 않음"})
        missing = []
        if not t["title"]:
            missing.append("제목")
        if not t["description"]:
            missing.append("설명")
        if not t["acceptance_criteria"]:
            missing.append("완료 조건")
        if missing:
            fails.append({"item": 9, "problem": f"{', '.join(missing)}이(가) 비어 있음", "fix": "빠진 칸을 채움"})
        due_date = t["due"].get("date")
        if due_date and meeting_date and due_date < meeting_date:
            t["warnings"].append(f"기한({due_date})이 회의일({meeting_date})보다 이름")
        return fails

    def _verify_checks(self, t: dict[str, Any], checks: list[Any], source: str, excluded: list[str]) -> list[dict[str, Any]]:
        """LLM 검수 지적을 코드로 걸러 확실한 것만 실패로 인정한다."""
        draft_text = normalize_ws(" ".join([t["title"], t["description"], *t["acceptance_criteria"]]))
        src = normalize_ws(source)
        fails: list[dict[str, Any]] = []
        notes: list[str] = []
        for c in checks:
            if c.passed or c.item not in LLM_ITEMS:
                continue
            quote = normalize_ws(c.quote)
            if not quote or quote not in draft_text:
                continue  # 초안에서 인용하지 못한 지적은 무시
            if c.item == 4 and quote in src:
                continue  # 원문에 있는 문구를 "원문에 없다"고 한 지적은 무시
            if c.item == 2 and not excluded:
                continue  # 제외·보류 항목이 없는 회의
            if c.item in ADVISORY_ITEMS:
                notes.append(f"[{c.item}] {c.problem} → {c.fix}")
                continue
            fails.append({"item": c.item, "problem": f"'{c.quote}': {c.problem}", "fix": c.fix})
        if notes:
            t["review_notes"] = notes
        return fails

    def _apply_fallback(self, t: dict[str, Any], items: set[int], feedback: list[str]) -> None:
        """보완 한도를 넘긴 표 티켓은 회의록 원문 기반 기본 초안으로 바꿔 발행한다."""
        if not t["title"] or 7 in items:
            t["title"] = t["action_text"]
        if not t["description"] or 4 in items:
            t["description"] = f"회의에서 정한 액션 아이템: {t['action_text']}"
        if not t["acceptance_criteria"] or 8 in items:
            t["acceptance_criteria"] = [f"{t['action_text']} 완료"]
        if not t["evidence_quotes"]:
            t["evidence_quotes"] = [t["action_text"]]
        t["review_status"] = REVIEW_APPROVED
        t["fallback"] = True
        t["review_notes"] = feedback
        t["feedback"] = []
        t["reason"] = f"보완 {t['revisions']}회 후에도 지적이 남아 기본 초안으로 발행"

    def _decide(self, t: dict[str, Any], fails: list[dict[str, Any]]) -> None:
        seen: set[tuple[int, str]] = set()
        unique = []
        for f in fails:
            key = (f["item"], f["problem"])
            if key not in seen:
                seen.add(key)
                unique.append(f)
        t["failed_items"] = unique
        items = {f["item"] for f in unique}
        # 검수 LLM 한 번의 판단으로는 반려하지 않는다. 반려는 근거 액션 아이템이 없는 경우(1번)만.
        # 2·3번(제외 항목·할 일 아님) 지적은 보완으로 돌리고, 한도까지 남으면 아래에서 처리한다.
        if items & REJECT_ITEMS:
            t["review_status"] = REVIEW_REJECTED
            t["action"] = "CREATE"
            t["reason"] = "반려: " + "; ".join(f["problem"] for f in unique if f["item"] in REJECT_ITEMS)
            return
        if unique:
            feedback = [f"[{f['item']}] {f['problem']} → {f['fix']}" for f in unique]
            if t["revisions"] >= self.settings.max_revisions:
                scope_doubt = bool(items & SCOPE_ITEMS) and t.get("source") == "llm"
                if self.settings.fallback_publish and not scope_doubt:
                    self._apply_fallback(t, items, feedback)
                    return
                t["review_status"] = REVIEW_NEEDS_CHECK
                t["action"] = "NEEDS_CHECK"
                t["reason"] = f"보완 {t['revisions']}회 후에도 미통과: " + "; ".join(feedback)
            else:
                t["review_status"] = REVIEW_REVISE
                t["feedback"] = feedback
            return
        t["review_status"] = REVIEW_APPROVED
        t["feedback"] = []
        t["reason"] = None
        t["review_notes"] = t.get("review_notes", [])

    async def review(self, state: AgentState) -> dict[str, Any]:
        if (stop := self._past_deadline(state)) is not None:
            return stop
        await self._progress(state, STAGE_REVIEWING)
        tickets = _copy(state["tickets"])
        source = state["original_content"]
        pending = [t for t in tickets if t["review_status"] == REVIEW_PENDING]

        to_llm: list[dict[str, Any]] = []
        code_fails: dict[str, list[dict[str, Any]]] = {}
        for t in pending:
            kind = t["expected"]["kind"]
            if kind == "hold":
                t["review_status"] = REVIEW_NEEDS_CHECK
                t["action"] = "NEEDS_CHECK"
                t["reason"] = f"회의록에 발행 보류로 표기됨 ({t['expected']['raw']})"
                continue
            if kind == "conditional_update" and self.settings.conditional_as_needs_check:
                t["review_status"] = REVIEW_NEEDS_CHECK
                t["action"] = "NEEDS_CHECK"
                t["reason"] = f"조건부 처리로 표기되어 확인 필요 ({t['expected']['raw']})"
                continue
            code_fails[t["task_item_id"]] = self._code_checks(t, state.get("meeting_date"))
            to_llm.append(t)

        llm_fails: dict[str, list[dict[str, Any]]] = {}
        for batch in _chunks(to_llm, self.settings.review_batch_size):
            drafts = [
                {
                    "work_key": t["work_key"] or t["task_item_id"],
                    "action": t["action_text"],
                    "title": t["title"],
                    "description": t["description"],
                    "acceptance_criteria": t["acceptance_criteria"],
                    "evidence_quotes": t["evidence_quotes"],
                }
                for t in batch
            ]
            context = {
                "source_note": "아래 초안은 모두 회의록 '액션 아이템' 표(회의에서 합의된 할 일)에서 만들었다"
                if all(t.get("source") == "table" for t in batch) else "일부 초안은 표 없이 본문에서 뽑았다",
                "excluded_or_held": state.get("excluded", []),
            }
            try:
                reviews = await self.llm.review(source, drafts, context)
            except AgentFatal as exc:
                return {"error_code": exc.code, "error_message": exc.message}
            by_key = {r.work_key: r for r in reviews}
            for t in batch:
                r = by_key.get(t["work_key"] or t["task_item_id"])
                if r is None:
                    t["review_notes"] = t.get("review_notes", []) + ["검수 결과가 누락되어 코드 점검만 적용함"]
                    llm_fails[t["task_item_id"]] = []
                    continue
                llm_fails[t["task_item_id"]] = self._verify_checks(t, r.checks, source, state.get("excluded", []))

        for t in to_llm:
            self._decide(t, code_fails.get(t["task_item_id"], []) + llm_fails.get(t["task_item_id"], []))
            if t["review_status"] != REVIEW_APPROVED:
                log.info("검수 %s → %s: %s", t["work_key"], t["review_status"],
                         t["reason"] or "; ".join(t["feedback"]))

        counts = {}
        for t in tickets:
            counts[t["review_status"]] = counts.get(t["review_status"], 0) + 1
        log.info("검수 결과: %s", counts)
        return {"tickets": tickets, "stage": STAGE_REVIEWING}

    # ------------------------------------------------------------ 4. 발행

    async def publish(self, state: AgentState) -> dict[str, Any]:
        await self._progress(state, STAGE_PUBLISHING)
        tickets = _copy(state["tickets"])
        fatal: str | None = None
        fatal_message: str | None = None
        for t in tickets:
            if t["review_status"] != REVIEW_APPROVED:
                t["publish_status"] = "BLOCKED"
                continue
            if fatal:
                t["publish_status"] = "FAILED"
                t["error_message"] = f"[{fatal}] 앞선 오류로 발행을 중단함"
                continue
            body = build_issue_body(t, state["meeting_id"], state.get("background", ""))
            try:
                res = await self.publisher.create_issue(
                    owner=state["owner"],
                    repo=state["repo"],
                    title=t["title"],
                    body=body,
                    labels=self.settings.issue_labels,
                    assignees=t["assignees"],
                )
            except ToolCallError as exc:
                t["publish_status"] = "UNKNOWN" if exc.ambiguous else "FAILED"
                t["error_message"] = f"[{exc.code}] {exc.message}"[:1000]
                if exc.code in FATAL_PUBLISH_CODES:
                    fatal, fatal_message = exc.code, exc.message
                continue
            if res.get("status") == "dry_run":
                # 백엔드는 SKIPPED를 "발행 생략"으로 저장한다 (NOT_STARTED는 발행 실패로 바뀐다)
                t["publish_status"] = "SKIPPED"
                t["reason"] = "DRY_RUN: 발행하지 않음"
                continue
            t["publish_status"] = "SUCCEEDED"
            t["github_issue_number"] = res.get("number")
            t["github_issue_url"] = res.get("url")
            dropped = res.get("dropped_assignees") or []
            if dropped:
                t["warnings"].append(f"GitHub에서 담당자 지정이 안 됨: {', '.join(dropped)}")
        update: dict[str, Any] = {"tickets": tickets, "stage": STAGE_PUBLISHING}
        if fatal:
            update["error_code"] = fatal
            update["error_message"] = fatal_message
        return update

    # ------------------------------------------------------------ 5. 결과 정리·보고

    def build_result(self, state: AgentState) -> dict[str, Any]:
        tickets = state.get("tickets", [])
        error_code = state.get("error_code")
        failed = bool(error_code) and error_code != NO_TICKETS
        items = []
        for t in tickets:
            review_status = t["review_status"]
            if review_status in (REVIEW_PENDING, REVIEW_REVISE):
                review_status = REVIEW_PENDING if failed else REVIEW_NEEDS_CHECK
            items.append({
                "task_item_id": t["task_item_id"],
                "work_key": t["work_key"],
                "title": t["title"] or t["action_text"],
                "content_json": {
                    "action_text": t["action_text"],
                    "description": t["description"],
                    "acceptance_criteria": t["acceptance_criteria"],
                    "evidence_quotes": t["evidence_quotes"],
                    "assignees": {
                        "names": t["assignee_names"],
                        "github": t["assignees"],
                        "unresolved": t["unresolved_assignees"],
                    },
                    "due": t["due"],
                    "expected": t["expected"],
                    "open_questions": t["open_questions"],
                    "review": {
                        "failed_items": [
                            {**f, "name": CHECK_NAMES.get(f["item"], "")} for f in t["failed_items"]
                        ],
                        "revisions": t["revisions"],
                        "fallback": t.get("fallback", False),
                        "notes": t.get("review_notes", []),
                    },
                    "warnings": t["warnings"],
                },
                "action": t["action"],
                "reason": t["reason"],
                "review_status": review_status,
                "publish_status": t["publish_status"],
                "github_issue_number": t["github_issue_number"],
                "github_issue_url": t["github_issue_url"],
                "error_message": t["error_message"],
            })
        summary = {
            "total": len(items),
            "approved": sum(1 for i in items if i["review_status"] == REVIEW_APPROVED),
            "rejected": sum(1 for i in items if i["review_status"] == REVIEW_REJECTED),
            "needsCheck": sum(1 for i in items if i["review_status"] == REVIEW_NEEDS_CHECK),
            "published": sum(1 for i in items if i["publish_status"] == "SUCCEEDED"),
            "publishFailed": sum(1 for i in items if i["publish_status"] == "FAILED"),
            "publishUnknown": sum(1 for i in items if i["publish_status"] == "UNKNOWN"),
        }
        return {
            "task_id": state["task_id"],
            "status": STATUS_FAILED if failed else STATUS_SUCCEEDED,
            "stage": STAGE_DONE if not failed else state.get("stage", STAGE_EXTRACTING),
            "error_code": error_code,
            "error_message": state.get("error_message"),
            "summary": summary,
            "items": items,
            "trace_id": state.get("trace_id"),
            "metrics": run_metrics(self.settings, self.llm, state.get("started_at")),
        }

    async def finish(self, state: AgentState) -> dict[str, Any]:
        result = self.build_result(state)
        try:
            payload = RunResult(**result).model_dump(by_alias=True, mode="json")
            await self.reporter.result(state["task_id"], payload)
        except Exception as exc:  # noqa: BLE001
            log.warning("결과 보고 실패: %s", exc)
        return {"result": result, "stage": result["stage"]}


# ---------------------------------------------------------------- 라우팅


def route_after_prepare(state: AgentState) -> str:
    return "finish" if state.get("error_code") or not state.get("tickets") else "draft"


def route_after_draft(state: AgentState) -> str:
    return "finish" if state.get("error_code") else "review"


def route_after_review(state: AgentState) -> str:
    if state.get("error_code"):
        return "finish"
    if any(t["review_status"] == REVIEW_REVISE for t in state["tickets"]):
        return "draft"
    return "publish"

