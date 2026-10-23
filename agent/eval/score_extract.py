"""추출 문항 채점: agent 결과 JSON(cli.py --out)을 정답(reference)과 비교한다.

연결 방법
1. 티켓의 제목/할 일 원문/근거 인용에 정답 업무 키(CSV-01 등)가 있으면 그 키로 연결한다.
2. 키로 연결되지 않은 정답은 제목 유사도와 담당자 일치로 남은 티켓과 짝짓는다 (임계값 이하는 연결하지 않음).
같은 정답에 연결된 두 번째 이후 티켓과 연결되지 않은 티켓은 "초과"로 센다.
유사도 연결은 근사치이므로, 프롬프트를 크게 바꾼 뒤에는 detail의 연결 결과를 사람이 한 번 훑어본다.
"""

from __future__ import annotations

import difflib
import json
import re
from pathlib import Path
from typing import Any

from .reference import EXTERNAL_PREFIX, NEEDS_CHECK, RefTask, Reference

MATCH_THRESHOLD = 0.4
OWNER_BONUS = 0.3


def _norm(text: str) -> str:
    return re.sub(r"[\s\W_]+", "", text or "")


def _item_text(item: dict[str, Any]) -> str:
    cj = item.get("contentJson") or {}
    return " ".join([item.get("title") or "", cj.get("action_text") or "", *(cj.get("evidence_quotes") or [])])


def _names(item: dict[str, Any]) -> list[str]:
    return list(((item.get("contentJson") or {}).get("assignees") or {}).get("names") or [])


def owner_ok(ref: RefTask, item: dict[str, Any], member_names: set[str]) -> bool:
    cj = item.get("contentJson") or {}
    names = _names(item)
    if ref.owner == NEEDS_CHECK:
        # 멤버 중 누구도 지정하지 않으면 정답 (이름 목록이 비었거나 호칭 원문만 남은 경우)
        return not any(n in member_names for n in names)
    if ref.owner.startswith(EXTERNAL_PREFIX):
        expected = _norm(ref.owner.removeprefix(EXTERNAL_PREFIX))
        github = (cj.get("assignees") or {}).get("github") or []
        return not github and any(expected in _norm(n) or _norm(n) in expected for n in names if _norm(n))
    return names == [ref.owner]


def due_ok(ref: RefTask, item: dict[str, Any]) -> tuple[bool, bool]:
    """(날짜 일치, 날짜와 시각 모두 일치). 정답에 시각이 없으면 시각은 비어 있어야 한다."""
    due = (item.get("contentJson") or {}).get("due") or {}
    date_ok = (due.get("date") or None) == ref.date
    return date_ok, date_ok and (due.get("time") or None) == ref.time


def _similarity(ref: RefTask, item: dict[str, Any]) -> float:
    cj = item.get("contentJson") or {}
    a = _norm(ref.title)
    sims = [difflib.SequenceMatcher(None, a, _norm(t)).ratio()
            for t in (item.get("title") or "", cj.get("action_text") or "") if t]
    score = max(sims, default=0.0)
    if ref.owner not in (NEEDS_CHECK,) and ref.owner in _names(item):
        score += OWNER_BONUS
    return score


def link_items(reference: Reference, items: list[dict[str, Any]]) -> tuple[dict[str, dict], list[dict]]:
    """정답 키 -> 티켓, 그리고 초과 티켓 목록."""
    keys = {t.key for t in reference.tasks}
    matched: dict[str, dict] = {}
    used: set[int] = set()
    for idx, item in enumerate(items):
        hits = [k for k in re.findall(r"[A-Z]{2,}\d*-\d+[A-Z]?", _item_text(item)) if k in keys]
        if hits and hits[0] not in matched:
            matched[hits[0]] = item
            used.add(idx)
    pairs = sorted(
        ((_similarity(t, it), t.key, idx) for t in reference.tasks if t.key not in matched
         for idx, it in enumerate(items) if idx not in used),
        reverse=True,
    )
    for score, key, idx in pairs:
        if score >= MATCH_THRESHOLD and key not in matched and idx not in used:
            matched[key] = items[idx]
            used.add(idx)
    extra = [it for idx, it in enumerate(items) if idx not in used]
    return matched, extra


def score_run(question: str, run: str, result: dict[str, Any], reference: Reference,
              member_names: set[str], forbidden: list[str] | None = None) -> dict[str, Any]:
    items = result.get("items") or []
    matched, extra = link_items(reference, items)
    owner_n = date_n = due_n = 0
    detail = []
    for task in reference.tasks:
        item = matched.get(task.key)
        if not item:
            detail.append({"key": task.key, "found": False})
            continue
        o = owner_ok(task, item, member_names)
        d, dt = due_ok(task, item)
        owner_n += o
        date_n += d
        due_n += dt
        cj = item.get("contentJson") or {}
        detail.append({
            "key": task.key, "found": True, "title": item.get("title"), "assignees": _names(item), "owner_ok": o,
            "due": cj.get("due"), "expected_due": task.due_raw, "due_date_ok": d, "due_ok": dt,
        })
    blob = json.dumps(items, ensure_ascii=False)
    leaked = [f for f in (forbidden or []) if f in blob]
    status = result.get("status")
    n_matched = len(matched)
    satisfied = (status == "SUCCEEDED" and len(items) == reference.expected_count and n_matched == len(reference.tasks)
                 and owner_n == n_matched and due_n == n_matched and not leaked)
    return {
        "question": question, "run": run, "status": status,
        "expected": reference.expected_count, "extracted": len(items), "matched": n_matched, "extra": len(extra),
        "owner_ok": owner_n, "due_date_ok": date_n, "due_ok": due_n,
        "count_match": len(items) == reference.expected_count, "forbidden_hit": leaked, "satisfied": satisfied,
        "durationMs": (result.get("metrics") or {}).get("durationMs"),
        "extra_titles": [e.get("title") for e in extra], "detail": detail,
    }


def aggregate(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """회의 실행 행들을 한 묶음 지표로 합친다 (baseline과 같은 합산 방식)."""
    def ratio(num: int, den: int) -> float | None:
        return num / den if den else None

    extracted = sum(r["extracted"] for r in rows)
    expected = sum(len(r["detail"]) for r in rows)
    matched = sum(r["matched"] for r in rows)
    owner = sum(r["owner_ok"] for r in rows)
    due = sum(r["due_ok"] for r in rows)
    times = [r["durationMs"] for r in rows if r.get("durationMs")]
    return {
        "runs": len(rows),
        "failed_runs": sum(1 for r in rows if r["status"] != "SUCCEEDED"),
        "expected_tasks": expected, "extracted": extracted, "matched": matched,
        "precision": ratio(matched, extracted),
        "recall": ratio(matched, expected),
        "owner_accuracy": ratio(owner, matched),
        "due_accuracy": ratio(due, matched),
        "owner_error_rate": None if not matched else 1 - owner / matched,
        "due_error_rate": None if not matched else 1 - due / matched,
        "count_match_rate": ratio(sum(r["count_match"] for r in rows), len(rows)),
        "satisfied_rate": ratio(sum(r["satisfied"] for r in rows), len(rows)),
        "avg_ms": sum(times) / len(times) if times else None,
    }


def result_files(results_dir: Path, question: str) -> list[Path]:
    return sorted(results_dir.glob(f"{question}_r*.json"))
