"""그래프 노드.

흐름: prepare(추출 준비·담당자·기한 정리) → draft(초안 작성) → review(검수)
      → (보완 필요 시 draft로, 최대 MAX_REVISIONS회) → dedupe(중복 판단) → finish(결과 정리·보고)
기본은 초안 저장에서 멈춘다 (기획_v16). 검수는 초안 보완과 카드 경고만 하고, 발행은 담당자가 확인한 뒤
일괄 발행(TASK-034)이 한다. PUBLISH_ON_RUN이면 Phase 1처럼 검수가 승인·반려를 정하고 dedupe 뒤에 publish로 발행한다.

LLM, 발행 도구, 백엔드 보고는 생성자로 주입한다. 테스트에서는 가짜를 넣는다.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import Counter
import uuid
from datetime import date
from typing import Any

import prompts
from config import Settings
from context import planning_text
from guard import SUSPICIOUS_WARNING, scan_draft
from extraction import NO_EVIDENCE_WARNING, from_llm, from_table, keep_verbatim
from parsing import parse_summary
from publisher import ToolCallError, build_issue_body, build_notion_page, build_slack_notice, pick_labels
from resolve import classify_expected, map_assignees, normalize_ws, parse_due
from schemas import ExtractedTodo, ProjectMember, RunResult
from state import (
    CHECK_NAMES,
    FATAL_PUBLISH_CODES,
    INPUT_TOO_LONG,
    PUBLISH_DRAFT,
    STEP_CONTEXT,
    STEP_DEDUPING,
    STEP_DISPATCHING,
    STEP_EXTRACTING,
    TARGET_GITHUB,
    TARGET_NOTION,
    TARGET_SLACK,
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

# 새 이슈를 색인에 넣을 때 기다리는 시간. 넘으면 색인 없이 넘어간다 (발행 결과는 그대로)
INDEX_TIMEOUT_SECONDS = 10

# 초안 단계 카드 경고 (초안을 다시 쓸 때마다 지우고 새로 붙인다)
LABEL_WARNING = "라벨 후보에 없어 뺀 라벨"
DRAFT_QUOTE_WARNING = "초안의 근거 인용 중 원문과 다른"
DRAFT_LENGTH_WARNING = "초안이 길이 한도를 넘어 잘라냄"
DRAFT_WARNING_PREFIXES = (LABEL_WARNING, DRAFT_QUOTE_WARNING, SUSPICIOUS_WARNING, DRAFT_LENGTH_WARNING)
MAX_TITLE, MAX_DESCRIPTION, MAX_LIST = 60, 1500, 5
# 초안 저장 모드에서 보완 한도 후에도 남은 검수 지적 (상세는 content_json.review.notes)
REVIEW_LEFT_WARNING = "자동 검수 지적"
MISSING_DRAFT_FEEDBACK = "[9] 초안 작성 결과에 이 할 일이 빠짐 → 이 할 일의 초안을 작성"


def _chunks(items: list[Any], size: int) -> list[list[Any]]:
    size = max(1, size)
    return [items[i : i + size] for i in range(0, len(items), size)]


def _copy(tickets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [dict(t) for t in tickets]


def _dedupe_metrics(tickets: list[dict[str, Any]]) -> dict[str, Any]:
    """중복 판단 지표: 판단 수, 건너뜀 수, 모델이 부른 도구 수, 관계별 수."""
    done = [t["dedupe"] for t in tickets if t.get("dedupe")]
    if not done:
        return {}
    return {"dedupe": {
        "judged": sum(1 for d in done if d["relation"]),
        "skipped": sum(1 for d in done if d["skipped"]),
        "toolCalls": sum(len(d["tool_calls"]) for d in done),
        "relations": dict(Counter(d["relation"] for d in done if d["relation"])),
        "indexed": sum(1 for d in done if d.get("indexed")),
    }}


def _open_same_issue(ticket: dict[str, Any]) -> int | None:
    """같은 작업(SAME)으로 판단된 열린 기존 이슈 번호. 없으면 None (새로 발행)."""
    d = ticket.get("dedupe") or {}
    if d.get("relation") == "SAME" and d.get("issue_state") == "open" and d.get("issue_number"):
        return d["issue_number"]
    return None


def run_metrics(settings: Settings, llm: Any, started_at: float | None, rag: dict[str, Any] | None = None) -> dict[str, Any]:
    """결과에 담는 실행 지표. 키는 백엔드 응답과 같은 camelCase."""
    from langsmith.utils import tracing_is_enabled

    return {
        "durationMs": int((time.time() - started_at) * 1000) if started_at else None,
        "promptVersion": prompts.PROMPT_VERSION,
        "model": settings.openai_model,
        "reviewModel": settings.review_model,
        "tracing": bool(tracing_is_enabled()),
        "rag": rag or {"status": "disabled" if not settings.rag_enabled else "unknown", "sync": None},
        **dict(getattr(llm, "stats", {}) or {}),  # 가짜 LLM에는 없다
    }


def new_ticket(todo: ExtractedTodo, members: list[ProjectMember], meeting_date: date | None, source: str = "table") -> dict[str, Any]:
    assignees = map_assignees(todo.assignees, members)
    due = parse_due(todo.due, meeting_date)
    expected = classify_expected(todo.expected)
    return {
        "task_item_id": str(uuid.uuid4()),
        "source": source,  # table: 회의록 액션 아이템 표, llm: 표가 없어 LLM이 뽑음
        "work_key": todo.work_key or None,
        "action_text": todo.action,
        "assignee_names": assignees.names,
        "assignees": assignees.logins,
        "unresolved_assignees": assignees.unresolved,
        "due": {"text": due.text, "date": due.date, "time": due.time},
        "expected": {"raw": expected.raw, "kind": expected.kind, "keys": expected.keys},
        "title": "",
        "description": "",
        "tasks": [],
        "acceptance_criteria": [],
        "evidence_quotes": list(todo.evidence_quotes),
        "labels": [],
        # 발행 대상과 사유 (기획 1.7.1). 초안 작성에서 정한다. 담당자가 카드에서 바꿀 수 있다 (TASK-031)
        "publish_target": TARGET_GITHUB,
        "target_reason": None,
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
        "warnings": list(todo.warnings),
        "dedupe": None,  # 중복 판단 결과 (dedupe.py). None이면 판단하지 않음
    }


class AgentNodes:
    def __init__(self, llm: Any, publisher: Any, reporter: Any, settings: Settings, issue_tools: Any | None = None,
                 context_tools: Any | None = None):
        self.llm = llm
        self.publisher = publisher
        self.reporter = reporter
        self.settings = settings
        self.issue_tools = issue_tools  # 중복 판단용 조회 도구 (issue_tools.IssueTools). 없으면 중복 확인 없이 발행
        self.context_tools = context_tools

    async def _progress(self, state: AgentState, stage: str, progress: dict[str, Any] | None = None) -> None:
        """진행 단계를 보고한다. progress를 주면 화면 단계와 세부 정보도 함께 보낸다 (기획_v16 REQ-007)."""
        try:
            await self.reporter.progress(state["task_id"], STATUS_RUNNING, stage, progress or state.get("progress"))
        except Exception as exc:  # noqa: BLE001 - 보고 실패로 작업을 멈추지 않는다
            log.warning("진행 보고 실패: %s", exc)

    @staticmethod
    def _step(state: AgentState, step: str, **detail: Any) -> dict[str, Any]:
        """지금까지의 세부 정보에 화면 단계와 새 값을 더한다. 노드는 이 값을 상태(progress)에도 돌려준다."""
        return {**(state.get("progress") or {}), "step": step, **detail}

    # ------------------------------------------------------------ 1. 추출 준비

    @staticmethod
    def _deadline_passed(state: AgentState) -> bool:
        deadline_at = state.get("deadline_at")
        return deadline_at is not None and time.time() > deadline_at

    def _past_deadline(self, state: AgentState) -> dict[str, Any] | None:
        """마감이 지났으면 작업을 끝내는 상태 변경을 돌려준다. 초안·검수 노드 시작 시에만 확인한다.

        발행은 시작하면 끝까지 한다. 중간에 끊으면 만들어진 이슈가 결과에서 빠질 수 있기 때문이다.
        """
        if not self._deadline_passed(state):
            return None
        limit = self.settings.task_deadline_seconds
        log.warning("작업 마감 초과 task=%s (%s초)", state.get("task_id"), limit)
        return {"error_code": TASK_TIMEOUT, "error_message": f"작업 마감 시간({limit}초)을 넘겨 중단함"}

    async def prepare(self, state: AgentState) -> dict[str, Any]:
        # collect가 이 dict를 채워 가므로, 시간 초과 때도 그때까지 읽은 이슈와 문서는 남는다
        project_context: dict[str, Any] = {"issues": [], "notionDocs": [], "warnings": []}
        if self.context_tools is not None:
            try:
                await asyncio.wait_for(
                    self.context_tools.collect(state["owner"], state["repo"], state.get("notion_page_url"),
                                               into=project_context),
                    timeout=self.settings.context_timeout_seconds,
                )
            except asyncio.TimeoutError:
                project_context["warnings"].append(
                    f"프로젝트 맥락 조회가 {self.settings.context_timeout_seconds}초 안에 끝나지 않음")
        context = self._step(state, STEP_CONTEXT, context={
            "members": len(state.get("members", [])),
            "issues": len(project_context["issues"]),
            "notionDocs": len(project_context["notionDocs"]),
            "warnings": project_context["warnings"],
        })
        await self._progress(state, STAGE_EXTRACTING, context)
        await self._progress(state, STAGE_EXTRACTING, {**context, "step": STEP_EXTRACTING})
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
        if parsed.rows:
            extraction = from_table(parsed)
        else:
            try:
                out = await self.llm.extract(state["original_content"])
            except AgentFatal as exc:
                return {"error_code": exc.code, "error_message": exc.message, "stage": STAGE_EXTRACTING}
            extraction = from_llm(parsed, out, state["meeting_id"], state["original_content"])
        progress = {**context, "step": STEP_EXTRACTING, "extraction": {
            "todos": len(extraction.todos), "decisions": len(extraction.decisions),
            "openQuestions": len(extraction.open_questions)}}
        if extraction.todos:
            await self._progress(state, STAGE_NORMALIZING, progress)
        members = [ProjectMember(**m) for m in state.get("members", [])]
        tickets = [new_ticket(todo, members, meeting_date, extraction.source) for todo in extraction.todos]
        log.info("추출 준비: 할 일 %d건, 결정 %d건, 미결 질문 %d건",
                 len(tickets), len(extraction.decisions), len(extraction.open_questions))
        return {
            "tickets": tickets,
            "background": parsed.background,
            "decisions": [d.model_dump() for d in extraction.decisions],
            "open_questions": extraction.open_questions,
            "excluded": extraction.excluded,
            "progress": progress,
            "project_context": project_context,
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
        labels = state.get("label_candidates") or self.settings.issue_labels
        # 추출 결과의 회의 단위 값. 초안의 미결 질문은 이 목록 안에서만 고른다
        context = {
            "decisions": [d["content"] for d in state.get("decisions", [])],
            "open_questions": state.get("open_questions", []),
            "project_context": state.get("project_context", {}),
        }
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
                    "evidence_quotes": t["evidence_quotes"],  # 추출 단계에서 원문과 대조한 근거
                }
                if t["review_status"] == REVIEW_REVISE and t["title"]:
                    item["current_draft"] = {
                        "title": t["title"],
                        "description": t["description"],
                        "tasks": t["tasks"],
                        "acceptance_criteria": t["acceptance_criteria"],
                        "labels": t["labels"],
                    }
                if t["review_status"] == REVIEW_REVISE:
                    item["feedback"] = t["feedback"]
                items.append(item)
            try:
                drafts = await self.llm.draft(state["original_content"], items, labels, context)
            except AgentFatal as exc:
                return {"error_code": exc.code, "error_message": exc.message}
            by_key = {d.work_key: d for d in drafts}
            for t in batch:
                d = by_key.get(t["work_key"] or t["task_item_id"])
                if t["review_status"] == REVIEW_REVISE:
                    t["revisions"] += 1
                    t["review_status"] = REVIEW_PENDING
                if d is not None:
                    # Phase 1 흐름(PUBLISH_ON_RUN)은 분류하지 않고 GitHub로만 발행한다 (baseline 재현)
                    self._apply_draft(t, d, labels, state["original_content"], context["open_questions"],
                                      classify=not self.settings.publish_on_run)
                elif t["revisions"] < self.settings.max_revisions:
                    # 초안이 빠진 할 일은 검수로 보내지 않고 다시 작성한다 (보완 한도를 같이 쓴다).
                    # 보완 중이던 티켓은 같은 피드백으로 다시 쓴다. 한도에 닿으면 검수가 처리한다
                    log.warning("초안 작성 결과에 %s가 빠짐 (보완 %d회)", t["work_key"] or t["task_item_id"], t["revisions"])
                    t["review_status"] = REVIEW_REVISE
                    if not t["title"]:
                        t["feedback"] = [MISSING_DRAFT_FEEDBACK]
        return {"tickets": tickets}

    @staticmethod
    def _apply_draft(t: dict[str, Any], d: Any, labels: list[str], source: str, open_questions: list[str],
                     classify: bool = True) -> None:
        # 초안 단계 경고는 초안을 다시 쓸 때마다 새로 계산한다 (보완할 때 같은 경고가 쌓이지 않게)
        t["warnings"] = [w for w in t["warnings"] if not w.startswith(DRAFT_WARNING_PREFIXES)]
        t["title"] = d.title.strip()
        t["description"] = d.description.strip()
        t["tasks"] = [x.strip() for x in d.tasks if x.strip()]
        t["acceptance_criteria"] = [c.strip() for c in d.acceptance_criteria if c.strip()]
        # 출력 검증: 길이 한도를 넘는 초안은 잘라내고, 회의록에 없던 URL/코드블록/명령어는 경고한다 (프롬프트 인젝션 방어)
        clipped = (len(t["title"]) > MAX_TITLE or len(t["description"]) > MAX_DESCRIPTION
                   or len(t["tasks"]) > MAX_LIST or len(t["acceptance_criteria"]) > MAX_LIST)
        if clipped:
            t["title"], t["description"] = t["title"][:MAX_TITLE], t["description"][:MAX_DESCRIPTION]
            t["tasks"], t["acceptance_criteria"] = t["tasks"][:MAX_LIST], t["acceptance_criteria"][:MAX_LIST]
            t["warnings"].append(DRAFT_LENGTH_WARNING)
        found = scan_draft([t["title"], t["description"], *t["tasks"], *t["acceptance_criteria"]], source)
        if found:
            t["warnings"].append(f"{SUSPICIOUS_WARNING}: {', '.join(found)[:200]}")
        t["labels"], dropped_labels = pick_labels(d.labels, labels)
        if dropped_labels:
            t["warnings"].append(f"{LABEL_WARNING}: {', '.join(dropped_labels)}")
        kept, dropped = keep_verbatim(d.evidence_quotes, source)
        if dropped:
            t["warnings"].append(f"{DRAFT_QUOTE_WARNING} {dropped}개를 제외함")
        # 초안의 근거가 모두 빠지면 추출 단계의 근거를 그대로 둔다. 원문에 없는 문장은 근거로 쓰지 않는다
        if kept:
            t["evidence_quotes"] = kept
        if t["evidence_quotes"]:
            t["warnings"] = [w for w in t["warnings"] if w != NO_EVIDENCE_WARNING]
        if classify:
            t["publish_target"] = d.publish_target
            t["target_reason"] = d.target_reason.strip() or None
        if t["publish_target"] == TARGET_NOTION:
            # 라벨은 GitHub 이슈에만 쓴다
            t["labels"] = []
            t["warnings"] = [w for w in t["warnings"] if not w.startswith(LABEL_WARNING)]
        allowed = {normalize_ws(q): q for q in open_questions}
        picked = [allowed[k] for q in d.open_questions if (k := normalize_ws(q)) in allowed]
        if len(picked) < len([q for q in d.open_questions if q.strip()]):
            log.info("추출된 미결 질문에 없는 초안 질문을 뺌: %s", t["work_key"] or t["task_item_id"])
        t["open_questions"] = list(dict.fromkeys(picked))

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

    def _verify_checks(self, t: dict[str, Any], checks: list[Any], source: str, excluded: list[str],
                       planning: str = "") -> list[dict[str, Any]]:
        """LLM 검수 지적을 코드로 걸러 확실한 것만 실패로 인정한다."""
        draft_text = normalize_ws(" ".join([t["title"], t["description"], *t["tasks"], *t["acceptance_criteria"]]))
        src = normalize_ws(source)
        fails: list[dict[str, Any]] = []
        notes: list[str] = []
        for c in checks:
            if c.passed or c.item not in LLM_ITEMS:
                continue
            quote = normalize_ws(c.quote)
            if not quote or quote not in draft_text:
                continue  # 초안에서 인용하지 못한 지적은 무시
            if c.item == 4 and (quote in src or (planning and quote in normalize_ws(planning))):
                # 원문이나 Notion 기획 문서(배경으로 준 맥락)에 있는 문구를 "원문에 없다"고 한 지적은 무시
                continue
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
        if items & {2, 4}:
            t["tasks"] = []  # 제외·보류 항목이나 원문에 없는 내용이 남았으면 상세 작업도 믿지 않는다
        if not t["acceptance_criteria"] or 8 in items:
            t["acceptance_criteria"] = [f"{t['action_text']} 완료"]
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
        if unique and not self.settings.publish_on_run:
            self._decide_draft(t, unique, items)
            return
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

    def _decide_draft(self, t: dict[str, Any], unique: list[dict[str, Any]], items: set[int]) -> None:
        """초안 저장 모드의 판정: 반려·확인 필요로 막지 않는다. 승인·반려는 담당자가 내 티켓에서 한다.

        보완할 수 있는 지적은 보완으로 돌리고, 한도를 넘기거나 보완으로 고칠 수 없는 지적(1번)은
        카드 경고와 검수 메모로 남긴 채 초안을 넘긴다. 기본 초안으로 바꾸지 않는다 (사람이 고치는 편이 낫다).
        """
        feedback = [f"[{f['item']}] {f['problem']} → {f['fix']}" for f in unique]
        if not items & REJECT_ITEMS and t["revisions"] < self.settings.max_revisions:
            t["review_status"] = REVIEW_REVISE
            t["feedback"] = feedback
            return
        t["review_status"] = REVIEW_APPROVED  # 자동 검수를 마침. 결과에는 담당자 확인 대기로 나간다
        t["feedback"] = []
        t["reason"] = None
        t["review_notes"] = feedback
        t["warnings"] = [w for w in t["warnings"] if not w.startswith(REVIEW_LEFT_WARNING)]
        t["warnings"].append(f"{REVIEW_LEFT_WARNING} {len(feedback)}개가 남아 확인 필요")

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
            if not self.settings.publish_on_run and kind in ("hold", "conditional_update"):
                # 초안 저장 모드: 발행을 막지 않고 카드 경고로 알린 뒤 다른 초안처럼 검수한다
                note = (f"회의록에 발행 보류로 표기됨 ({t['expected']['raw']})" if kind == "hold"
                        else f"조건부 처리로 표기되어 확인 필요 ({t['expected']['raw']})")
                if note not in t["warnings"]:
                    t["warnings"].append(note)
            elif kind == "hold":
                t["review_status"] = REVIEW_NEEDS_CHECK
                t["action"] = "NEEDS_CHECK"
                t["reason"] = f"회의록에 발행 보류로 표기됨 ({t['expected']['raw']})"
                continue
            elif kind == "conditional_update" and self.settings.conditional_as_needs_check:
                t["review_status"] = REVIEW_NEEDS_CHECK
                t["action"] = "NEEDS_CHECK"
                t["reason"] = f"조건부 처리로 표기되어 확인 필요 ({t['expected']['raw']})"
                continue
            code_fails[t["task_item_id"]] = self._code_checks(t, state.get("meeting_date"))
            to_llm.append(t)

        llm_fails: dict[str, list[dict[str, Any]]] = {}
        # 초안이 배경으로 반영한 Notion 문서 문구를 "원문에 없다"고 한 지적은 인정하지 않는다
        planning = planning_text([
            {"title": d.get("title") or "", "text": d.get("markdown") or ""}
            for d in (state.get("project_context") or {}).get("notionDocs", [])])
        for batch in _chunks(to_llm, self.settings.review_batch_size):
            drafts = [
                {
                    "work_key": t["work_key"] or t["task_item_id"],
                    "action": t["action_text"],
                    "title": t["title"],
                    "description": t["description"],
                    "tasks": t["tasks"],
                    "acceptance_criteria": t["acceptance_criteria"],
                    "evidence_quotes": t["evidence_quotes"],
                }
                for t in batch
            ]
            context = {
                "source_note": "아래 초안은 모두 회의록 '액션 아이템' 표(회의에서 합의된 할 일)에서 만들었다"
                if all(t.get("source") == "table" for t in batch) else "일부 초안은 표 없이 본문에서 뽑았다",
                "excluded_or_held": state.get("excluded", []),
                "project_context": state.get("project_context", {}),
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
                llm_fails[t["task_item_id"]] = self._verify_checks(
                    t, r.checks, source, state.get("excluded", []), planning=planning)

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

    # ------------------------------------------------------------ 3-1. 중복 판단

    async def dedupe(self, state: AgentState) -> dict[str, Any]:
        """승인된 티켓마다 모델이 조회 도구를 골라 기존 이슈와의 관계를 판단한다 (dedupe.py).

        판단은 발행 노드가 쓴다: 같은 작업의 열린 이슈는 연결, 나머지는 새로 발행하고 본문에 관련 이슈를 남긴다.
        판단이 없거나 실패한 티켓은 그냥 새로 발행한다.
        """
        tickets = _copy(state["tickets"])
        # 중복 판단은 GitHub로 분류된 티켓만 한다 (기획 1.7.2)
        targets = [t for t in tickets
                   if t["review_status"] == REVIEW_APPROVED and t.get("publish_target", TARGET_GITHUB) == TARGET_GITHUB]
        rag = (state.get("rag") or {}).get("status", "disabled")
        if not targets or rag == "disabled":
            return {"tickets": tickets}  # 중복 검수 단계를 보여주지 않는다 (기능이 꺼짐, 기획 3.1)
        skipped = None
        if self.issue_tools is None:
            skipped = "rag_unavailable"
        elif not hasattr(self.llm, "tool_chat"):
            skipped = "no_tool_model"
        elif self._deadline_passed(state):
            skipped = "deadline"  # 마감이 지나면 판단은 건너뛰고 발행은 그대로 한다
        if skipped:
            from dedupe import _result

            for t in targets:
                t["dedupe"] = _result(skipped=skipped)
            log.info("중복 판단 건너뜀: %s", skipped)
            return {"tickets": tickets}

        from dedupe import build_judge_graph, judge_ticket

        graph = build_judge_graph(
            self.llm.tool_chat(), self.issue_tools.judge_tools(),
            max_calls=self.settings.dedupe_max_tool_calls, max_same=self.settings.dedupe_max_same_tool)
        gate = asyncio.Semaphore(max(1, self.settings.dedupe_concurrency))
        counts = {"done": 0, "total": len(targets), "linked": 0}
        await self._progress(state, STAGE_REVIEWING, self._step(state, STEP_DEDUPING, dedupe=dict(counts)))

        async def one(t: dict[str, Any]) -> None:
            async with gate:
                t["dedupe"] = await judge_ticket(graph, t, self.settings)
            # 같은 작업의 열린 이슈가 있으면 발행할 때 새로 만들지 않고 연결한다 (화면의 "연결" 건수)
            counts["done"] += 1
            counts["linked"] += 1 if _open_same_issue(t) else 0
            await self._progress(state, STAGE_REVIEWING, self._step(state, STEP_DEDUPING, dedupe=dict(counts)))

        await asyncio.gather(*(one(t) for t in targets))
        log.info("중복 판단: %s", dict(Counter(t["dedupe"]["relation"] or t["dedupe"]["skipped"] for t in targets)))
        return {"tickets": tickets, "progress": self._step(state, STEP_DEDUPING, dedupe=counts)}

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
            existing = _open_same_issue(t)
            if existing:
                # 같은 작업의 열린 이슈가 있으면 새로 만들지 않고 그 이슈로 연결한다 (GitHub에 쓰지 않음)
                t["publish_status"] = "SUCCEEDED"
                t["github_issue_number"] = existing
                t["github_issue_url"] = f"https://github.com/{state['owner']}/{state['repo']}/issues/{existing}"
                t["reason"] = f"기존 이슈 #{existing}과 같은 작업이라 새로 만들지 않음"
                t["dedupe"] = {**t["dedupe"], "linked_existing": True}
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
                    labels=t["labels"],
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
            # 저장소에 없는 라벨은 MCP가 빼고 만든다. 담당자가 알 수 있게 경고로 남긴다 (TASK-046)
            dropped_labels = res.get("dropped_labels") or []
            if dropped_labels:
                t["warnings"].append(f"저장소에 없는 라벨이라 빠짐: {', '.join(dropped_labels)}")
            if self.issue_tools is not None and t["github_issue_number"]:
                # 다음 실행의 중복 판단에서 찾을 수 있게 색인에 넣는다. 실패해도 발행 결과는 그대로
                indexed = await self._index(t["github_issue_number"])
                if t.get("dedupe") is not None:
                    t["dedupe"] = {**t["dedupe"], "indexed": indexed}
        update: dict[str, Any] = {"tickets": tickets, "stage": STAGE_PUBLISHING}
        if fatal:
            update["error_code"] = fatal
            update["error_message"] = fatal_message
        return update

    async def _index(self, number: int) -> bool:
        """새 이슈를 색인에 넣는다. rag-mcp가 응답하지 않아도 발행이 멈추지 않게 시간 한도를 둔다."""
        try:
            return await asyncio.wait_for(self.issue_tools.index(number), timeout=INDEX_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            log.warning("이슈 색인이 %s초 안에 끝나지 않아 건너뜀 #%s", INDEX_TIMEOUT_SECONDS, number)
            return False

    # ------------------------------------------------------------ 5. 결과 정리·보고

    def build_result(self, state: AgentState) -> dict[str, Any]:
        tickets = state.get("tickets", [])
        error_code = state.get("error_code")
        failed = bool(error_code) and error_code != NO_TICKETS
        draft = not self.settings.publish_on_run and not failed
        items = []
        for t in tickets:
            review_status = t["review_status"]
            publish_status = t["publish_status"]
            if draft:
                # 초안 저장 모드: 모든 티켓이 담당자 확인 대기 초안이다. 검수 지적은 경고와 review.notes에 있다
                review_status, publish_status = REVIEW_PENDING, PUBLISH_DRAFT
            elif review_status in (REVIEW_PENDING, REVIEW_REVISE):
                review_status = REVIEW_PENDING if failed else REVIEW_NEEDS_CHECK
            items.append({
                "task_item_id": t["task_item_id"],
                "work_key": t["work_key"],
                "title": t["title"] or t["action_text"],
                "content_json": {
                    "action_text": t["action_text"],
                    "description": t["description"],
                    "tasks": t["tasks"],
                    "acceptance_criteria": t["acceptance_criteria"],
                    "labels": t["labels"],
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
                    "dedupe": t.get("dedupe"),
                    "publish_target": t.get("publish_target", TARGET_GITHUB),
                    "target_reason": t.get("target_reason"),
                    # 발행할 때 쓰는 것과 같은 본문. 발행 전 초안 저장과 미리보기에 쓴다
                    "issue_body": build_issue_body(t, state["meeting_id"], state.get("background", ""))
                    if t.get("publish_target", TARGET_GITHUB) == TARGET_GITHUB else None,
                    "notion_page": build_notion_page(t, state["meeting_id"], state.get("background", ""))
                    if t.get("publish_target") == TARGET_NOTION else None,
                },
                "action": t["action"],
                "reason": t["reason"],
                "review_status": review_status,
                "publish_status": publish_status,
                "github_issue_number": t["github_issue_number"],
                "github_issue_url": t["github_issue_url"],
                "error_message": t["error_message"],
            })
        if draft and (tickets or state.get("decisions")):
            items.append(self._slack_notice(state, tickets))
        summary = {
            "total": len(items),
            "drafts": sum(1 for i in items if i["publish_status"] == PUBLISH_DRAFT),
            "approved": sum(1 for i in items if i["review_status"] == REVIEW_APPROVED),
            "rejected": sum(1 for i in items if i["review_status"] == REVIEW_REJECTED),
            "needsCheck": sum(1 for i in items if i["review_status"] == REVIEW_NEEDS_CHECK),
            "published": sum(1 for i in items if i["publish_status"] == "SUCCEEDED"),
            "publishFailed": sum(1 for i in items if i["publish_status"] == "FAILED"),
            "publishUnknown": sum(1 for i in items if i["publish_status"] == "UNKNOWN"),
            "linked": sum(1 for i in items if (i["content_json"]["dedupe"] or {}).get("linked_existing")),
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
            "metrics": {**run_metrics(self.settings, self.llm, state.get("started_at"), state.get("rag")),
                        "context": {
                            "issues": len((state.get("project_context") or {}).get("issues", [])),
                            "notionDocs": len((state.get("project_context") or {}).get("notionDocs", [])),
                            "warnings": (state.get("project_context") or {}).get("warnings", []),
                        },
                        **_dedupe_metrics(tickets)},
        }

    @staticmethod
    def _slack_notice(state: AgentState, tickets: list[dict[str, Any]]) -> dict[str, Any]:
        """회의 결과 공지 티켓 (기획_v16 REQ-022). 회의 하나당 하나이고, 받는 사람은 업로더다 (REQ-010)."""
        decisions = [d["content"] for d in state.get("decisions", [])]
        notice = build_slack_notice(state.get("meeting_title", ""), state.get("meeting_date"), decisions, tickets,
                                    linked=sum(1 for t in tickets if _open_same_issue(t)),
                                    members=[m["name"] for m in state.get("members", [])])
        title = f"회의 결과 공지: {state.get('meeting_title') or state['meeting_id']}"
        return {
            # 같은 작업은 같은 ID라서 다시 보고해도 공지가 두 개 저장되지 않는다
            "task_item_id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"meet-to-ticket:{state['task_id']}:slack-notice")),
            "work_key": f"{state['meeting_id']}-notice",
            "title": title[:255],
            "content_json": {
                "publish_target": TARGET_SLACK,
                "target_reason": "회의 결과 공지는 Slack으로 보낸다 (기획 1.7.1)",
                "description": notice["text"],
                "slack": notice,
                "decisions": decisions,
                "assignees": {"names": [], "github": [], "unresolved": []},
                "warnings": [],
                "dedupe": None,
            },
            "action": "CREATE",
            "reason": "회의 결과 공지",
            "review_status": REVIEW_PENDING,
            "publish_status": PUBLISH_DRAFT,
            "github_issue_number": None,
            "github_issue_url": None,
            "error_message": None,
        }

    async def finish(self, state: AgentState) -> dict[str, Any]:
        result = self.build_result(state)
        if result["status"] != STATUS_FAILED and not self.settings.publish_on_run:
            # 결과를 보내면 백엔드가 담당자별 티켓으로 저장한다 (TASK-022)
            await self._progress(state, STAGE_REVIEWING, self._step(state, STEP_DISPATCHING))
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
    return "dedupe"

