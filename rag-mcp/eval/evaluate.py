"""Run a repeatable retrieval evaluation against an already indexed repository.

Usage:
    python -m eval.evaluate --owner MCP-Capstone --repo mcp-capstone
"""

from __future__ import annotations

import argparse
import json
import statistics
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any


Case = dict[str, Any]
Search = Callable[[str], list[dict[str, Any]]]


def calculate_metrics(cases: Iterable[Case], search: Search, k: int = 5) -> dict[str, Any]:
    """Calculate Recall@k, MRR, and the returned semantic-score distribution."""
    rows: list[dict[str, Any]] = []
    scores: list[float] = []

    for case in cases:
        results = search(case["query"])[:k]
        rank = next(
            (index for index, result in enumerate(results, start=1) if result["number"] == case["expected_issue"]),
            None,
        )
        scores.extend(float(result["score"]) for result in results)
        rows.append({**case, "rank": rank, "found": rank is not None})

    total = len(rows)
    found = sum(row["found"] for row in rows)
    return {
        "cases": total,
        "recall_at_k": round(found / total, 4) if total else 0.0,
        "mrr": round(sum(1 / row["rank"] for row in rows if row["rank"]) / total, 4) if total else 0.0,
        "score_distribution": {
            "count": len(scores),
            "min": round(min(scores), 4) if scores else None,
            "median": round(statistics.median(scores), 4) if scores else None,
            "mean": round(statistics.fmean(scores), 4) if scores else None,
            "max": round(max(scores), 4) if scores else None,
        },
        "results": rows,
    }


def results_search(tool: Callable[..., dict[str, Any]], owner: str, repo: str, k: int) -> Search:
    """search_similar_issues는 {"repository", "index", "results": [...]}를 돌려주므로 results만 꺼낸다."""
    return lambda query: tool(owner, repo, query, k)["results"]


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate issue search quality")
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--k", type=int, default=5, choices=range(1, 11))
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("cases.json"))
    args = parser.parse_args()

    from server import search_similar_issues

    cases = json.loads(args.cases.read_text())
    report = calculate_metrics(
        cases,
        results_search(search_similar_issues, args.owner, args.repo, args.k),
        args.k,
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
