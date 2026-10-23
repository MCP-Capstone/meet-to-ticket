"""LangGraph 그래프 조립."""

from __future__ import annotations

from typing import Any

from langgraph.graph import END, START, StateGraph

from nodes import AgentNodes, route_after_draft, route_after_prepare, route_after_review
from state import AgentState


def build_graph(nodes: AgentNodes, checkpointer: Any | None = None):
    """checkpointer를 주면 노드가 끝날 때마다 상태를 thread_id(= taskId) 단위로 저장한다."""
    g = StateGraph(AgentState)
    g.add_node("prepare", nodes.prepare)
    g.add_node("draft", nodes.draft)
    g.add_node("review", nodes.review)
    g.add_node("dedupe", nodes.dedupe)
    if nodes.settings.publish_on_run:
        g.add_node("publish", nodes.publish)
    g.add_node("finish", nodes.finish)

    g.add_edge(START, "prepare")
    g.add_conditional_edges("prepare", route_after_prepare, ["draft", "finish"])
    g.add_conditional_edges("draft", route_after_draft, ["review", "finish"])
    g.add_conditional_edges("review", route_after_review, ["draft", "dedupe", "finish"])
    if nodes.settings.publish_on_run:
        # Phase 1 흐름: 검수를 통과한 티켓을 바로 발행한다 (baseline 재현용)
        g.add_edge("dedupe", "publish")
        g.add_edge("publish", "finish")
    else:
        # 기본: 초안 저장에서 멈춘다. 발행은 담당자가 확인한 뒤 일괄 발행(TASK-034)이 한다
        g.add_edge("dedupe", "finish")
    g.add_edge("finish", END)
    return g.compile(checkpointer=checkpointer)
