"""발행 직전 중복 재대조 (TASK-042, 기획 문서 1.7.2 "대조는 초안 생성 시 1회, 발행 직전 1회").

초안을 만든 뒤 담당자가 확인하기까지 몇 시간에서 며칠이 걸린다. 그 사이 다른 사람이 같은 이슈를 만들었을 수 있어
발행 직전에 한 번 더 대조한다. 일괄 발행(TASK-034)이 저장소마다 이 함수를 한 번 부르고, 돌려준 처리 유형대로 발행한다.

- 대조 전에 색인을 한 번 증분 동기화한다 (협의 13번). 실패하거나 시간을 넘기면 기존 색인으로 계속한다.
- 판정 루프와 한도는 초안 생성 때(dedupe.py)와 같다. 판단에 실패해도 발행을 막지 않는다.
- 최종 처리 유형은 handling.after_recheck가 정한다.
"""

from __future__ import annotations

import asyncio
import logging
from collections import Counter
from typing import Any

from handling import after_recheck

log = logging.getLogger("agent.recheck")


async def recheck(
    tickets: list[dict[str, Any]],
    *,
    issue_tools: Any | None,
    llm: Any,
    settings: Any,
) -> dict[str, Any]:
    """저장소 1개의 확인된 티켓을 다시 대조하고 티켓마다 recheck와 handling을 채워 돌려준다.

    tickets: 결과 contentJson 형태(action_text, evidence_quotes, expected, title, work_key)에
             담당자가 확인한 처리 유형 handling({type, issue_number, edited})을 더한 dict.
             edited가 true면 담당자가 카드에서 직접 바꾼 것이라 재대조가 덮지 않는다. 원본은 바꾸지 않는다.
    반환: {"tickets": [...], "sync": 색인 동기화 결과 또는 None, "skipped": 재대조 전체를 건너뛴 이유 또는 None}
    """
    out = [dict(t) for t in tickets]
    skipped = None
    if not out:
        return {"tickets": out, "sync": None, "skipped": None}
    if issue_tools is None:
        skipped = "rag_unavailable"
    elif not hasattr(llm, "tool_chat"):
        skipped = "no_tool_model"
    if skipped:
        from dedupe import _result

        for t in out:
            t["recheck"] = _result(skipped=skipped)
            t["handling"] = after_recheck(t.get("handling"), t["recheck"])
        log.info("발행 직전 재대조 건너뜀: %s", skipped)
        return {"tickets": out, "sync": None, "skipped": skipped}

    sync = await _sync(issue_tools, settings)

    from dedupe import build_judge_graph, judge_ticket

    graph = build_judge_graph(
        llm.tool_chat(), issue_tools.judge_tools(),
        max_calls=settings.dedupe_max_tool_calls, max_same=settings.dedupe_max_same_tool)
    gate = asyncio.Semaphore(max(1, settings.dedupe_concurrency))

    async def one(t: dict[str, Any]) -> None:
        async with gate:
            t["recheck"] = await judge_ticket(graph, t, settings)
        t["handling"] = after_recheck(t.get("handling"), t["recheck"])

    await asyncio.gather(*(one(t) for t in out))
    log.info("발행 직전 재대조: %s, 연결로 바뀜 %s건",
             dict(Counter(t["recheck"]["relation"] or t["recheck"]["skipped"] for t in out)),
             sum(1 for t in out if t["handling"]["changed"]))
    return {"tickets": out, "sync": sync, "skipped": None}


async def _sync(issue_tools: Any, settings: Any) -> dict[str, Any] | None:
    try:
        return await asyncio.wait_for(issue_tools.sync(), timeout=settings.rag_sync_timeout_seconds)
    except asyncio.TimeoutError:
        log.warning("재대조 전 색인 갱신이 %s초 안에 끝나지 않아 기존 색인으로 진행", settings.rag_sync_timeout_seconds)
        return None
    except Exception:  # 연결 끊김 등 어떤 실패도 재대조와 발행을 막지 않는다
        log.warning("재대조 전 색인 갱신에 실패해 기존 색인으로 진행", exc_info=True)
        return None
