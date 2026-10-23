"""중복 판단 문항(ev41 ~ ev46) 채점. 대조 저장소의 시드 이슈 목록은 dataset/<버전>/dedupe_seed_issues.md 참고."""

from __future__ import annotations

from typing import Any

from .score_extract import ticket_items


def actual_handling(item: dict[str, Any]) -> str:
    """결과의 처리 유형을 CREATE / LINK / UPDATE / CREATE+REF 중 하나로 읽는다.

    새 코드는 dedupe.handling(type, issue_number)을 남긴다. 없으면 phase1-end 형식(linked_existing, relation)으로 읽는다.
    """
    d = (item.get("contentJson") or {}).get("dedupe") or {}
    h = d.get("handling")
    if h:
        if h.get("type") in ("LINK", "UPDATE"):
            return h["type"]
        return "CREATE+REF" if h.get("issue_number") else "CREATE"
    if d.get("linked_existing"):
        return "LINK"
    if d.get("relation") in ("SAME", "CHANGE", "RELATED") and d.get("issue_number"):
        return "CREATE+REF"
    return "CREATE"


def score_run(question: str, run: str, result: dict[str, Any], expect: dict[str, Any]) -> dict[str, Any]:
    items = ticket_items(result)
    num = expect["issue"]
    # 문항마다 업무는 1건. 대상 이슈를 가리킨 티켓을 우선 채점 대상으로 본다
    main = next((i for i in items if ((i.get("contentJson") or {}).get("dedupe") or {}).get("issue_number") == num), None)
    if main is None and items:
        main = items[0]
    dd = ((main.get("contentJson") or {}).get("dedupe") or {}) if main else {}
    got_rel, got_num = dd.get("relation"), dd.get("issue_number")
    got_h = actual_handling(main) if main else None
    relation_ok = got_rel == expect["relation"]
    target_ok = got_num == num
    return {
        "question": question, "run": run, "status": result.get("status"), "items": len(items),
        "expected": f"{expect['relation']} #{num}" if num else expect["relation"],
        "got": f"{got_rel} #{got_num}" if got_num else str(got_rel),
        "relation_ok": relation_ok, "target_ok": target_ok, "judgment_ok": relation_ok and target_ok,
        "expected_handling": expect["handling"], "got_handling": got_h,
        "handling_ok": got_h == expect["handling"] and target_ok,
        "skipped": dd.get("skipped"), "tool_calls": len(dd.get("tool_calls") or []),
        "durationMs": (result.get("metrics") or {}).get("durationMs"),
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    n = len(rows)
    times = [r["durationMs"] for r in rows if r.get("durationMs")]

    def ratio(k: int) -> float | None:
        return k / n if n else None

    return {
        "runs": n,
        "failed_runs": sum(1 for r in rows if r["status"] != "SUCCEEDED"),
        "relation_accuracy": ratio(sum(r["relation_ok"] for r in rows)),
        "target_accuracy": ratio(sum(r["target_ok"] for r in rows)),
        "judgment_accuracy": ratio(sum(r["judgment_ok"] for r in rows)),
        "handling_rate": ratio(sum(r["handling_ok"] for r in rows)),
        "extra_tickets": sum(max(0, r["items"] - 1) for r in rows),
        "avg_ms": sum(times) / len(times) if times else None,
    }
