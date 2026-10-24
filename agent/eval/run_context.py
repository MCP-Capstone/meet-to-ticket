"""맥락 반영 평가를 한 명령으로 돌리고 채점표를 출력한다.

agent 폴더에서 (OPENAI_API_KEY 필요, LLM 비용 발생)
    python -m eval.run_context --label current --runs 3
    python -m eval.run_context --label current --plan       # 실행할 문항과 캐시 적중만 보고 끝냄 (비용 없음)
    python -m eval.run_context --label current --score-only

문항마다 같은 회의를 두 번 돌린다: 고정 맥락을 넣은 실행(ctx)과 맥락 없는 실행(noctx, 추출 안정성 대조용).
맥락은 cli.py --context로 넣으므로 mcp-server, rag-mcp, Notion이 필요 없다.
실행, 캐시, dry-run 고정은 run_eval과 같은 함수를 쓴다. 결과는 eval/results/context-v1/<label>/.
agent/.env의 RAG_ENABLED가 켜져 있으면 실행하지 않는다. 켜지면 맥락 없음 실행에도 실제 기존 이슈 맥락과
중복 판단이 끼어 대조군이 깨진다 (cli.py가 .env를 우선해 읽어 실행기가 끌 수 없다).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

from . import run_eval, score_context
from .run_eval import (_sha, code_hash, default_executor, existing_reusable, prompt_hash, prune_stale_runs, run_jobs,
                       settings_env)
from .score_context import CONTEXT_VERSION, VARIANTS, context_dir, load_expect

# 맥락 평가는 대조 저장소를 쓰지 않지만, RAG가 켜져 있어도 실제 저장소를 건드리지 않게 같은 저장소를 붙인다
SAFE_REPO = "meet-to-ticket-eval"


def plan_jobs(cases: list[str], runs: int, results_dir: Path, base: Path | None = None,
              agent_dir: Path = run_eval.AGENT_DIR, models: dict[str, str] | None = None) -> list[dict[str, Any]]:
    ds = context_dir(base)
    prompts, code = prompt_hash(agent_dir), code_hash(agent_dir)
    models = run_eval.model_ids(agent_dir=agent_dir) if models is None else models
    settings = settings_env(agent_dir)
    members = ds / "members.json"
    jobs = []
    for case in cases:
        inp = ds / "inputs" / f"{case}_summary.md"
        ctx = ds / "contexts" / f"{case}_context.json"
        for variant in VARIANTS:
            context = ctx if variant == "ctx" else None
            # 맥락 내용이 바뀌면 다시 실행하도록 키에 넣는다
            seed = "context:" + (_sha(ctx.read_bytes())[:16] if context else "none")
            for n in range(1, runs + 1):
                key = run_eval.cache_key(inp, members, SAFE_REPO, prompts, models, code, n, settings, seed)
                jobs.append({"question": f"{case}_{variant}", "run": n, "input": inp, "members": members,
                             "repo": SAFE_REPO, "context": context, "dedupe": False,
                             "out": results_dir / f"{case}_{variant}_r{n}.json", "key": key,
                             "cached": (run_eval.CACHE_DIR / f"{key}.json").exists()})
    return jobs


def main(argv: list[str] | None = None, executor: run_eval.Executor = default_executor) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--label", default="latest", help=f"결과 폴더 이름: eval/results/{CONTEXT_VERSION}/<label>")
    p.add_argument("--runs", type=int, default=3, help="문항, 변형(ctx/noctx)마다 반복 횟수")
    p.add_argument("--cases", help="쉼표로 구분한 문항 ID만 실행")
    p.add_argument("--plan", action="store_true", help="실행하지 않고 계획과 캐시 적중만 출력")
    p.add_argument("--score-only", action="store_true", help="실행 없이 기존 결과 폴더만 채점")
    p.add_argument("--no-cache", action="store_true", help="캐시를 읽지 않음 (결과는 캐시에 저장)")
    p.add_argument("--force", action="store_true", help="이미 있는 결과 파일도 다시 실행")
    p.add_argument("--allow-backend-report", action="store_true",
                   help=".env에 BACKEND_BASE_URL이 있어도 실행 (평가 실행이 백엔드에 보고될 수 있음)")
    args = p.parse_args(argv)
    if args.runs < 1:
        p.error("--runs 는 1 이상이어야 합니다")

    all_cases = list(load_expect()["cases"])
    only = [c.strip() for c in args.cases.split(",") if c.strip()] if args.cases else None
    if only and (unknown := [c for c in only if c not in all_cases]):
        p.error(f"없는 문항입니다: {', '.join(unknown)}")
    cases = [c for c in all_cases if not only or c in only]
    results_dir = run_eval.RESULTS_ROOT / CONTEXT_VERSION / args.label

    stats: dict[str, list[str]] = {"failed": []}
    if not args.score_only:
        findings = run_eval.env_file_findings()
        for level, msg in findings:
            print(f"[{'중단' if level == 'block' and not args.allow_backend_report else '경고'}] {msg}")
        if not args.plan and any(lv == "block" for lv, _ in findings) and not args.allow_backend_report:
            p.error("agent/.env의 BACKEND_BASE_URL을 비우거나 --allow-backend-report를 주세요")
        if not args.plan and any(msg.startswith("RAG_ENABLED") for _, msg in findings):
            p.error("agent/.env의 RAG_ENABLED를 false로 바꾸세요 (맥락 없음 대조 실행에 기존 이슈 맥락이 끼어듭니다)")
        jobs = plan_jobs(cases, args.runs, results_dir)
        print(f"맥락 평가셋 {CONTEXT_VERSION}, 문항 {len(cases)}개 x (ctx, noctx) x {args.runs}회 = {len(jobs)}건, "
              f"결과 {results_dir}")
        if args.plan:
            for j in jobs:
                tag = ("재사용" if not args.force and existing_reusable(j)
                       else "캐시" if not args.no_cache and not args.force and j["cached"] else "실행")
                print(f"  [{tag}] {j['question']} r{j['run']}")
            return 0
        if results_dir.is_dir():
            prune_stale_runs(results_dir, [j["question"] for j in jobs[::args.runs]], args.runs)
        stats = run_jobs(jobs, executor, use_cache=not args.no_cache, force=args.force,
                         cache_dir=run_eval.CACHE_DIR)
        print(f"\n실행 {len(stats['ran'])}, 캐시 {len(stats['cached'])}, 기존 결과 {len(stats['skipped'])}, "
              f"실패 {len(stats['failed'])}")

    if not results_dir.is_dir():
        p.error(f"결과 폴더가 없습니다: {results_dir}")
    summary = score_context.score_dir(results_dir, cases=cases)
    print()
    print(score_context.format_report(summary))
    (results_dir / "scores.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    c = summary.get("context") or {}
    failed = c.get("failed_runs", 0) + c.get("noctx_failed_runs", 0)
    return 1 if stats["failed"] or failed or summary.get("corrupt") or summary.get("missing") or not c else 0


if __name__ == "__main__":
    sys.exit(main())
