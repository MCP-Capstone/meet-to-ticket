"""중복 판단: 모델이 조회 도구를 골라 부르고, 기존 이슈와의 관계를 제출한다.

판단 루프는 작은 LangGraph 그래프다.

    model ──(조회 도구 호출)──▶ tools (ToolNode) ──▶ model ── … ──(submit_decision)──▶ 끝

- 모델에게는 조회 도구(issue_tools.IssueTools.judge_tools)와 submit_decision만 준다. 이슈 생성 도구는 주지 않는다.
- 한도(전체 도구 호출 수, 같은 도구 호출 수, 시간)는 코드가 센다. 넘으면 판단 없이 끝내고, 발행 단계는 새로 발행한다.
- 도구 오류는 ToolNode가 ToolMessage로 모델에게 돌려준다 (handle_tool_errors). 모델이 보고 다른 도구를 고른다.
- 모델이 고른 이슈 번호는 도구 결과에서 실제로 본 번호여야 한다. 아니면 그 판단은 버린다.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Annotated, Any, Literal, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.prebuilt import ToolNode
from pydantic import BaseModel, Field, ValidationError

import prompts
from guard import neutralize
from handling import decide
from issue_tools import READ_ONLY_TOOLS

log = logging.getLogger("agent.dedupe")

SUBMIT = "submit_decision"
Relation = Literal["SAME", "CHANGE", "RELATED", "UNRELATED"]


class Decision(BaseModel):
    # reason을 relation보다 먼저 둔다: 모델은 인자를 앞에서부터 쓰므로, 근거를 먼저 쓰고 그 결론대로 판정해야 판정과 근거가 어긋나지 않는다 (#344)
    reason: str = Field(description="판정 전에 먼저 쓴다. 기존 이슈가 다루는 요소와 새 작업이 바꾸는 요소가 같은지, 어떤 점이 같고 다른지 한두 문장")
    relation: Relation = Field(
        description="SAME: 같은 대상에 같은 변경 / CHANGE: 같은 요소에서 기존 이슈 본문에 적힌 값/방향을 다르게 바꿈 / RELATED: 기존 이슈가 만들거나 바꾸는 대상(같은 기능/데이터)을 직접 다루는 다른 작업(같은 화면의 다른 요소는 UNRELATED) / "
                    "UNRELATED: 다루는 대상이 다르거나 후보가 없음. 같은 화면이나 단어가 겹치는 것만으로는 관계가 아님")
    issue_number: int | None = Field(None, description="관계가 있는 기존 이슈 번호. 도구 결과에서 본 번호만. UNRELATED면 비움")
    before: str | None = Field(None, description="CHANGE일 때만: 기존 이슈의 값이나 방향 (예: 버튼 모양 네모)")
    after: str | None = Field(None, description="CHANGE일 때만: 새 작업의 값이나 방향 (예: 버튼 모양 세모)")


class JudgeState(TypedDict):
    messages: Annotated[list, add_messages]
    calls: list[str]            # 지금까지 부른 조회 도구 이름 (한도 계산)
    decision: dict | None
    stopped: str | None         # 판단 없이 끝난 이유


def _read_decision(args: dict[str, Any]) -> Decision:
    """제출 인자를 Decision으로 읽는다. reason만 빠졌으면 판정은 버리지 않고 사유를 비워 둔다.

    reason을 relation 앞에 둔 뒤로 모델이 드물게 reason을 빼고 제출한다. 사유가 없다고 판정까지 버리면
    중복 판단이 건너뛰어져 같은 작업이 새로 발행된다 (#344).
    """
    try:
        return Decision(**args)
    except ValidationError as exc:
        if all(e["type"] == "missing" and e["loc"] == ("reason",) for e in exc.errors()):
            return Decision(**{**args, "reason": ""})
        raise


def _submit_tool() -> Any:
    from langchain_core.tools import StructuredTool

    async def submit(**_: Any) -> str:
        return "제출됨"

    return StructuredTool.from_function(
        coroutine=submit, name=SUBMIT, args_schema=Decision,
        description="판단을 제출하고 끝낸다. 도구로 충분히 확인했거나 후보가 없을 때 부른다.")


def build_judge_graph(chat: Any, tools: list[Any], *, max_calls: int, max_same: int) -> Any:
    """판단 루프 그래프. 티켓마다 다시 만들지 않고 한 번 만들어 같이 쓴다."""
    # 코드 수준 allowlist: 읽기 전용 도구만 모델에 묶고 ToolNode에도 넣는다. 그 밖의 이름은 모델이 불러도 실행되지 않는다
    removed = [t.name for t in tools if t.name not in READ_ONLY_TOOLS]
    if removed:
        log.warning("읽기 전용 allowlist 밖의 도구를 중복 판단에서 뺌: %s", ", ".join(removed))
    tools = [t for t in tools if t.name in READ_ONLY_TOOLS]
    # tool_choice="required": 매 턴 도구(조회 또는 제출)를 하나 이상 부르게 해 글로만 답하고 끝나지 않게 한다
    bound = chat.bind_tools([*tools, _submit_tool()], tool_choice="required")

    async def model(state: JudgeState) -> dict[str, Any]:
        ai = await bound.ainvoke(state["messages"])
        tool_calls = getattr(ai, "tool_calls", None) or []
        submit = next((c for c in tool_calls if c["name"] == SUBMIT), None)
        if submit is not None:
            try:
                return {"messages": [ai], "decision": _read_decision(submit["args"]).model_dump()}
            except ValidationError:
                return {"messages": [ai], "stopped": "invalid_decision"}
        if not tool_calls:
            return {"messages": [ai], "stopped": "no_decision"}
        calls = state["calls"] + [c["name"] for c in tool_calls]
        if len(calls) > max_calls:
            return {"messages": [ai], "calls": calls, "stopped": "tool_limit"}
        if any(calls.count(name) > max_same for name in set(calls)):
            return {"messages": [ai], "calls": calls, "stopped": "same_tool_limit"}
        return {"messages": [ai], "calls": calls}

    def route(state: JudgeState) -> str:
        return END if state.get("decision") or state.get("stopped") else "tools"

    g = StateGraph(JudgeState)
    g.add_node("model", model)
    g.add_node("tools", ToolNode(tools, handle_tool_errors=True))
    g.add_edge(START, "model")
    g.add_conditional_edges("model", route, ["tools", END])
    g.add_edge("tools", "model")
    return g.compile()


def _user_message(ticket: dict[str, Any]) -> str:
    """판단 입력. 초안 설명은 넘기지 않는다.

    초안에는 회의 전체 맥락이 덧붙어 있어, 모델이 "새 작업이 더 넓다"고 보고 같은 작업을 RELATED로 판단했다 (#107).
    회의록 원문의 할 일과 근거 인용만 넘긴다.
    """
    expected = ticket.get("expected") or {}
    # 값은 회의록(데이터)에서 온 것이다. 템플릿이 데이터 태그로 감싸므로 태그를 닫는 문자열만 무력화한다
    return prompts.DEDUPE_USER.format(**{k: neutralize(v) for k, v in dict(
        title=ticket.get("title") or ticket.get("action_text", ""),
        action=ticket.get("action_text", ""),
        evidence=" / ".join(ticket.get("evidence_quotes") or []) or "-",
        keys=", ".join(expected.get("keys") or []) or "없음",
        expected=expected.get("raw") or "없음",
    ).items()})


def _seen_issues(messages: list[Any]) -> dict[int, dict[str, Any]]:
    """도구 결과(JSON)에서 본 이슈 번호와 상태. 모델이 고른 번호를 검증하는 데 쓴다."""
    seen: dict[int, dict[str, Any]] = {}
    for m in messages:
        if getattr(m, "type", "") != "tool":
            continue
        try:
            data = json.loads(m.content)
        except (TypeError, ValueError):
            continue
        for item in (data.get("results") or []) + (data.get("issues") or []):
            if isinstance(item, dict) and isinstance(item.get("number"), int):
                seen[item["number"]] = {k: item.get(k) for k in ("title", "state", "closed_at")}
    return seen


def _tool_calls(messages: list[Any]) -> list[dict[str, Any]]:
    return [
        {"tool": c["name"], "args": c["args"]}
        for m in messages if getattr(m, "type", "") == "ai"
        for c in (getattr(m, "tool_calls", None) or []) if c["name"] != SUBMIT
    ]


def _result(decision: dict | None = None, *, calls: list | None = None, skipped: str | None = None,
            issue: dict | None = None) -> dict[str, Any]:
    """티켓의 판단 결과 (결과 contentJson.dedupe). relation이 없으면 판단 없이 새로 발행한다.

    handling은 판정으로 정한 처리 유형이다 (handling.py, 기획_v16 1.7.2 처리 유형 표).
    """
    change = decision is not None and decision["relation"] == "CHANGE"
    result = {
        "relation": decision["relation"] if decision else None,
        "issue_number": decision.get("issue_number") if decision else None,
        "issue_state": (issue or {}).get("state"),
        "issue_title": (issue or {}).get("title"),
        "reason": decision["reason"] if decision else None,
        # 카드의 변경 전후 표시와 갱신 섹션에 쓴다 (REQ-026)
        "before": decision.get("before") if change else None,
        "after": decision.get("after") if change else None,
        "tool_calls": calls or [],
        "skipped": skipped,
    }
    result["handling"] = decide(result)
    return result


async def judge_ticket(graph: Any, ticket: dict[str, Any], settings: Any) -> dict[str, Any]:
    """티켓 하나를 판단한다. 시간 한도·도구 한도·오류는 판단 없음(skipped)으로 돌려준다."""
    from langchain_core.messages import HumanMessage, SystemMessage

    state = {
        "messages": [SystemMessage(prompts.DEDUPE_SYSTEM), HumanMessage(_user_message(ticket))],
        "calls": [], "decision": None, "stopped": None,
    }
    config = {"recursion_limit": 2 * settings.dedupe_max_tool_calls + 6, "run_name": "dedupe",
              "metadata": {"work_key": ticket.get("work_key")}}
    try:
        out = await asyncio.wait_for(graph.ainvoke(state, config=config), timeout=settings.dedupe_timeout_seconds)
    except asyncio.TimeoutError:
        log.warning("중복 판단 시간 초과 %s (%s초)", ticket.get("work_key"), settings.dedupe_timeout_seconds)
        return _result(skipped="timeout")
    except Exception as exc:  # noqa: BLE001 - 판단 실패는 발행을 막지 않는다
        log.warning("중복 판단 실패 %s: %s", ticket.get("work_key"), exc)
        return _result(skipped=f"error: {str(exc)[:200]}")

    calls = _tool_calls(out["messages"])
    decision = out.get("decision")
    if decision is None:
        return _result(calls=calls, skipped=out.get("stopped") or "no_decision")
    if decision["relation"] == "UNRELATED":
        return _result({**decision, "issue_number": None}, calls=calls)
    seen = _seen_issues(out["messages"])
    number = decision.get("issue_number")
    if number not in seen:
        # 도구 결과에서 보지 않은 번호는 믿지 않는다 (모델이 지어낸 번호로 연결되지 않게)
        log.warning("중복 판단이 확인되지 않은 이슈 #%s를 가리켜 버림 %s", number, ticket.get("work_key"))
        return _result(calls=calls, skipped="unverified_issue")
    return _result(decision, calls=calls, issue=seen[number])
