"""결과 폴더를 채점해 지표표를 출력한다.

agent 폴더에서
    python -m eval.score results/v2.1/after
    python -m eval.score results/v2.1/after --baseline results/v2.1/baseline/scores.json

결과 파일 이름은 {문항ID}_r{N}.json (cli.py --out 형식). 문항당 여러 회차는 합산한다.
"""

from __future__ import annotations

import argparse
import json
import sys
import unicodedata
from pathlib import Path
from typing import Any

from . import score_dedupe, score_extract
from .reference import (DEFAULT_VERSION, EVAL_DIR, dataset_dir, load_manifest, load_member_names, load_reference,
                        question_ids)

TARGETS_PATH = EVAL_DIR / "targets.json"


def score_dir(results_dir: Path, version: str = DEFAULT_VERSION, base: Path | None = None) -> dict[str, Any]:
    manifest = load_manifest(version, base)
    members = load_member_names(version, base)
    dedupe_expect = manifest["dedupe"]
    forbidden = manifest.get("forbidden_text", {})
    extract_rows, dedupe_rows, missing, corrupt = [], [], [], []
    for q in question_ids(version, base):
        files = score_extract.result_files(results_dir, q)
        if not files:
            missing.append(q)
            continue
        for path in files:
            run = path.stem.rsplit("_r", 1)[-1]
            try:
                result = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(result, dict):
                    raise ValueError("JSON 객체가 아님")
            except (OSError, ValueError) as exc:
                # 깨진 파일은 실패한 실행으로 세고, 어떤 파일인지 보고한다
                corrupt.append(f"{path.name} ({exc})")
                result = {"status": "FAILED", "items": [], "metrics": {}}
            if q in dedupe_expect:
                dedupe_rows.append(score_dedupe.score_run(q, run, result, dedupe_expect[q]))
            else:
                extract_rows.append(score_extract.score_run(
                    q, run, result, load_reference(q, version, base), members, forbidden.get(q)))
    return {
        "version": version,
        "missing": missing,
        "corrupt": corrupt,
        "extract": score_extract.aggregate(extract_rows) if extract_rows else None,
        "dedupe": score_dedupe.aggregate(dedupe_rows) if dedupe_rows else None,
        "extract_rows": extract_rows,
        "dedupe_rows": dedupe_rows,
    }


def _fmt(field: str, value: float | None) -> str:
    if value is None:
        return "-"
    return f"{value / 1000:.1f}초" if field == "avg_ms" else f"{value * 100:.1f}%"


def _pad(text: str, width: int) -> str:
    """한글은 두 칸으로 세어 표 열을 맞춘다."""
    used = sum(2 if unicodedata.east_asian_width(c) in "WF" else 1 for c in text)
    return text + " " * max(1, width - used)


def check_targets(summary: dict[str, Any], baseline: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    """목표 대비 판정 행. op가 '<=x'이면 baseline 평균의 배수 이하인지 본다 (baseline 필요)."""
    rows = []
    for m in json.loads(TARGETS_PATH.read_text(encoding="utf-8"))["metrics"]:
        value = (summary.get(m["group"]) or {}).get(m["field"])
        before = ((baseline or {}).get(m["group"]) or {}).get(m["field"])
        if m["op"] == "<=x":
            limit = before * m["target"] if before else None
            ok = None if value is None or limit is None else value <= limit
            target_text = f"baseline의 {m['target']}배 이하" + (f" ({_fmt(m['field'], limit)})" if limit else " (baseline 필요)")
        else:
            ok = None if value is None else (value >= m["target"] if m["op"] == ">=" else value <= m["target"])
            target_text = f"{_fmt(m['field'], m['target'])} {'이상' if m['op'] == '>=' else '이하'}"
        rows.append({**m, "value": value, "before": before, "ok": ok, "target_text": target_text})
    return rows


def format_report(summary: dict[str, Any], baseline: dict[str, Any] | None = None) -> str:
    lines = [f"평가셋 {summary['version']}"]
    if baseline:
        lines.append(f"비교 대상 평가셋 {baseline['version']}")
    ex, de = summary.get("extract"), summary.get("dedupe")
    if ex:
        lines.append(f"추출 {ex['runs']}회 (실패 {ex['failed_runs']}회), 기대 업무 {ex['expected_tasks']}건, "
                     f"추출 {ex['extracted']}건, 연결 {ex['matched']}건, 건수 일치 {_fmt('', ex['count_match_rate'])}, "
                     f"문항 충족 {_fmt('', ex['satisfied_rate'])}")
        if ex.get("evidence_leak_runs"):
            lines.append(f"경고: 금지 문자열이 근거 인용/경고/이슈 본문 근거 칸에 남은 실행 {ex['evidence_leak_runs']}회 (발행 본문 필드는 아님, 사람이 확인)")
    if de:
        lines.append(f"중복 판단 {de['runs']}회 (실패 {de['failed_runs']}회), 판정 {_fmt('', de['relation_accuracy'])}, "
                     f"대상 {_fmt('', de['target_accuracy'])}, 추가 티켓 {de['extra_tickets']}건")
    if summary.get("corrupt"):
        lines.append(f"경고: 읽을 수 없는 결과 파일 {len(summary['corrupt'])}개 (실패한 실행으로 계산): "
                     + ", ".join(summary["corrupt"]))
    if summary["missing"]:
        lines.append(f"결과 없는 문항 {len(summary['missing'])}개: {', '.join(summary['missing'])}")
    lines.append("")
    widths = (11, 32, 42, 10, 10)
    cols = ["구분", "지표", "목표", "결과"] + (["이전"] if baseline else [])
    head = "".join(_pad(c, w) for c, w in zip(cols, widths)) + "판정"
    lines += [head, "-" * 90]
    for r in check_targets(summary, baseline):
        mark = {True: "달성", False: "미달", None: "측정 불가"}[r["ok"]]
        cells = [r["role"], r["label"], r["target_text"], _fmt(r["field"], r["value"])]
        if baseline:
            cells.append(_fmt(r["field"], r["before"]))
        lines.append("".join(_pad(c, w) for c, w in zip(cells, widths)) + mark)
    for o in json.loads(TARGETS_PATH.read_text(encoding="utf-8"))["operational"]:
        cells = [o["role"], o["label"], o["target"], "운영 측정"]
        lines.append("".join(_pad(c, w) for c, w in zip(cells, widths)) + f"({o['note']})")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("results_dir", help="{문항ID}_r{N}.json이 들어 있는 폴더")
    p.add_argument("--version", default=DEFAULT_VERSION)
    p.add_argument("--baseline", help="이전 측정의 scores.json (같은 평가셋 버전만 비교)")
    p.add_argument("--out", help="채점 결과 JSON 경로 (기본: <results_dir>/scores.json)")
    args = p.parse_args(argv)

    results_dir = Path(args.results_dir)
    if not results_dir.is_dir():
        p.error(f"결과 폴더가 없습니다: {results_dir}")
    if not dataset_dir(args.version).is_dir():
        p.error(f"평가셋 버전이 없습니다: {args.version}")
    summary = score_dir(results_dir, args.version)
    baseline = None
    if args.baseline:
        baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8"))
        if baseline.get("version") != summary["version"]:
            p.error(f"평가셋 버전이 달라 비교하지 않습니다: {baseline.get('version')} / {summary['version']}")
    print(format_report(summary, baseline))
    out = Path(args.out) if args.out else results_dir / "scores.json"
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n채점 결과 저장: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
