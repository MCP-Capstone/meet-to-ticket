"""평가셋 전체를 한 명령으로 돌리고 채점표를 출력한다.

agent 폴더에서 (OPENAI_API_KEY 필요, LLM 비용 발생)
    python -m eval.run_eval --label after --runs 3
    python -m eval.run_eval --label after --plan          # 실행할 명령과 캐시 적중만 보고 끝냄 (비용 없음)
    python -m eval.run_eval --label pr-smoke --smoke      # 소수 스모크 (1회)

항상 cli.py --dry-run 으로만 실행한다. GitHub에 발행하지 않으며, 끄는 옵션도 없다.
중복 판단 문항(ev41 ~ ev46)은 mcp-server(8000)와 rag-mcp(8002)가 떠 있고 대조 저장소 시드 이슈가 있어야 한다.

결과 캐시 키: 입력 요약본, 명단, 프롬프트 해시, 모델 ID, (대조 저장소), agent 코드 해시, 회차 번호.
키가 같으면 LLM을 다시 부르지 않고 저장된 결과를 복사한다. 실패한 실행은 캐시하지 않는다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

from . import score
from .reference import DEFAULT_VERSION, EVAL_DIR, dataset_dir, load_manifest, question_ids

AGENT_DIR = EVAL_DIR.parent
CACHE_DIR = EVAL_DIR / ".cache"
RESULTS_ROOT = EVAL_DIR / "results"
MODEL_ENV = ("OPENAI_MODEL", "OPENAI_REVIEW_MODEL", "OPENAI_DEDUPE_MODEL", "OPENAI_FALLBACK_MODEL")
# PR에서 돌리는 소수 스모크: 상대 기한, 담당자 모호, 0건, 프롬프트 인젝션
SMOKE = ("ev11_relative_due", "ev16_ambiguous_assignee", "ev21_zero_action", "ev29_prompt_injection")

Executor = Callable[[list[str], Path, dict[str, str]], int]


def _sha(*parts: bytes) -> str:
    h = hashlib.sha256()
    for p in parts:
        h.update(len(p).to_bytes(8, "big") + p)
    return h.hexdigest()


def prompt_hash(agent_dir: Path = AGENT_DIR) -> str:
    files = sorted((agent_dir / "prompts").glob("*.md"))
    return _sha(*[x for f in files for x in (f.name.encode(), f.read_bytes())])[:16]


def code_hash(agent_dir: Path = AGENT_DIR) -> str:
    """agent 코드(프롬프트 제외, 테스트와 eval 제외)가 바뀌면 결과도 달라질 수 있으므로 키에 넣는다."""
    files = sorted(agent_dir.glob("*.py"))
    return _sha(*[x for f in files for x in (f.name.encode(), f.read_bytes())])[:16]


def model_ids(env: dict[str, str] | None = None, agent_dir: Path | None = None) -> dict[str, str]:
    """실행에 쓰일 모델 ID. agent 설정을 읽을 수 있으면 설정값, 아니면 환경 변수.

    다른 체크아웃(--agent-dir)의 설정은 읽지 않으므로 환경 변수 OPENAI_*로 모델을 명시한다.
    """
    env = os.environ if env is None else env
    if agent_dir is not None and agent_dir.resolve() != AGENT_DIR.resolve():
        return {k: env.get(k, "") for k in MODEL_ENV}
    try:
        sys.path.insert(0, str(AGENT_DIR))
        from config import load_env, load_settings  # noqa: PLC0415

        load_env()
        s = load_settings()
        return {"OPENAI_MODEL": s.openai_model, "OPENAI_REVIEW_MODEL": s.review_model,
                "OPENAI_DEDUPE_MODEL": s.dedupe_model, "OPENAI_FALLBACK_MODEL": s.fallback_model}
    except Exception:  # noqa: BLE001 - 설정을 못 읽으면 환경 변수 값으로 키를 만든다
        return {k: env.get(k, "") for k in MODEL_ENV}


def cache_key(input_path: Path, members_path: Path, repo: str | None, prompts: str, models: dict[str, str],
              code: str, run: int) -> str:
    return _sha(input_path.read_bytes(), members_path.read_bytes(), (repo or "").encode(), prompts.encode(),
                json.dumps(models, sort_keys=True).encode(), code.encode(), str(run).encode())[:24]


def build_command(input_path: Path, members_path: Path, out_path: Path, repo: str | None) -> list[str]:
    """항상 --dry-run 을 붙인다. 이 함수 밖에서 명령을 만들지 않는다."""
    cmd = [sys.executable, "cli.py", str(input_path), "--members", str(members_path), "--dry-run", "--out", str(out_path)]
    if repo:
        cmd += ["--repo", repo]
    return cmd


def default_executor(cmd: list[str], cwd: Path, env: dict[str, str]) -> int:
    return subprocess.run(cmd, cwd=cwd, env=env, check=False).returncode


def plan_jobs(questions: list[str], runs: int, results_dir: Path, version: str, dedupe: dict[str, Any],
              dedupe_repo: str, agent_dir: Path = AGENT_DIR, base: Path | None = None,
              models: dict[str, str] | None = None) -> list[dict[str, Any]]:
    ds = dataset_dir(version, base)
    prompts, code = prompt_hash(agent_dir), code_hash(agent_dir)
    models = model_ids(agent_dir=agent_dir) if models is None else models
    jobs = []
    for q in questions:
        for n in range(1, runs + 1):
            inp, members = ds / "inputs" / f"{q}_summary.md", ds / "members.json"
            repo = dedupe_repo if q in dedupe else None
            out = results_dir / f"{q}_r{n}.json"
            key = cache_key(inp, members, repo, prompts, models, code, n)
            jobs.append({"question": q, "run": n, "input": inp, "members": members, "repo": repo, "out": out,
                         "key": key, "cached": (CACHE_DIR / f"{key}.json").exists()})
    return jobs


def run_jobs(jobs: list[dict[str, Any]], executor: Executor = default_executor, *, use_cache: bool = True,
             force: bool = False, agent_dir: Path = AGENT_DIR, cache_dir: Path | None = None,
             log: Callable[[str], None] = print) -> dict[str, list[str]]:
    cache_dir = cache_dir or CACHE_DIR
    env = {**os.environ, "DRY_RUN": "true"}
    stats: dict[str, list[str]] = {"ran": [], "cached": [], "skipped": [], "failed": []}
    cache_dir.mkdir(parents=True, exist_ok=True)
    for j in jobs:
        name = f"{j['question']}_r{j['run']}"
        out: Path = j["out"]
        out.parent.mkdir(parents=True, exist_ok=True)
        cached = cache_dir / f"{j['key']}.json"
        if out.exists() and not force:
            stats["skipped"].append(name)
            continue
        if use_cache and not force and cached.exists():
            shutil.copyfile(cached, out)
            stats["cached"].append(name)
            log(f"[캐시] {name}")
            continue
        log(f"[실행] {name}")
        code = executor(build_command(j["input"], j["members"], out, j["repo"]), agent_dir, env)
        status = None
        if out.exists():
            try:
                status = json.loads(out.read_text(encoding="utf-8")).get("status")
            except ValueError:
                status = None
        if code != 0 or status is None:
            stats["failed"].append(name)
            log(f"[실패] {name} (종료 코드 {code})")
            continue
        stats["ran"].append(name)
        if status == "SUCCEEDED":
            shutil.copyfile(out, cached)
    return stats


def select_questions(all_ids: list[str], dedupe_ids: set[str], *, group: str, only: list[str] | None,
                     smoke: bool) -> list[str]:
    if only:
        unknown = [q for q in only if q not in all_ids]
        if unknown:
            raise SystemExit(f"없는 문항입니다: {', '.join(unknown)}")
        return [q for q in all_ids if q in only]
    if smoke:
        return [q for q in all_ids if q in SMOKE]
    if group == "extract":
        return [q for q in all_ids if q not in dedupe_ids]
    if group == "dedupe":
        return [q for q in all_ids if q in dedupe_ids]
    return all_ids


def main(argv: list[str] | None = None, executor: Executor = default_executor) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--version", default=DEFAULT_VERSION, help="평가셋 버전")
    p.add_argument("--label", default="latest", help="결과 폴더 이름: eval/results/<버전>/<label>")
    p.add_argument("--runs", type=int, default=3, help="문항당 반복 횟수 (baseline은 3)")
    p.add_argument("--group", choices=["all", "extract", "dedupe"], default="all")
    p.add_argument("--questions", help="쉼표로 구분한 문항 ID만 실행")
    p.add_argument("--smoke", action="store_true", help="소수 스모크 문항만 1회 실행 (PR 확인용)")
    p.add_argument("--plan", action="store_true", help="실행하지 않고 계획과 캐시 적중만 출력")
    p.add_argument("--score-only", action="store_true", help="실행 없이 기존 결과 폴더만 채점")
    p.add_argument("--no-cache", action="store_true", help="캐시를 읽지 않음 (결과는 캐시에 저장)")
    p.add_argument("--force", action="store_true", help="이미 있는 결과 파일도 다시 실행")
    p.add_argument("--baseline", help="비교할 이전 scores.json")
    p.add_argument("--agent-dir", help="cli.py를 실행할 agent 폴더 (기본: 이 저장소의 agent). "
                                       "phase1-end 같은 다른 체크아웃을 잴 때 쓴다")
    args = p.parse_args(argv)
    agent_dir = Path(args.agent_dir).resolve() if args.agent_dir else AGENT_DIR
    if not (agent_dir / "cli.py").is_file():
        p.error(f"cli.py가 없는 agent 폴더입니다: {agent_dir}")

    if args.runs < 1:
        p.error("--runs 는 1 이상이어야 합니다")
    if not dataset_dir(args.version).is_dir():
        p.error(f"평가셋 버전이 없습니다: {args.version}")
    manifest = load_manifest(args.version)
    ids = question_ids(args.version)
    questions = select_questions(ids, set(manifest["dedupe"]), group=args.group,
                                 only=args.questions.split(",") if args.questions else None, smoke=args.smoke)
    runs = 1 if args.smoke else args.runs
    results_dir = RESULTS_ROOT / args.version / args.label

    stats: dict[str, list[str]] = {"failed": []}
    if not args.score_only:
        jobs = plan_jobs(questions, runs, results_dir, args.version, manifest["dedupe"], manifest["dedupe_repo"],
                         agent_dir=agent_dir)
        print(f"평가셋 {args.version}, 문항 {len(questions)}개 x {runs}회 = {len(jobs)}건, 결과 {results_dir}")
        print(f"프롬프트 {prompt_hash(agent_dir)} 코드 {code_hash(agent_dir)} (DRY-RUN 고정, GitHub 쓰기 없음)")
        if any(j["repo"] for j in jobs):
            print("중복 판단 문항 포함: mcp-server(8000), rag-mcp(8002), 대조 저장소 시드 이슈가 필요합니다")
        if args.plan:
            for j in jobs:
                tag = "있음" if j["out"].exists() else ("캐시" if j["cached"] else "실행")
                print(f"  [{tag}] {j['question']} r{j['run']}" + (f" (--repo {j['repo']})" if j["repo"] else ""))
            if jobs:
                print("명령 예: " + " ".join(["python", *build_command(jobs[0]["input"], jobs[0]["members"],
                                                                      jobs[0]["out"], jobs[0]["repo"])[1:]]))
            return 0
        stats = run_jobs(jobs, executor, use_cache=not args.no_cache, force=args.force, agent_dir=agent_dir)
        print(f"\n실행 {len(stats['ran'])}, 캐시 {len(stats['cached'])}, 기존 결과 {len(stats['skipped'])}, 실패 {len(stats['failed'])}")
        if stats["failed"]:
            print("실패: " + ", ".join(stats["failed"]))

    if not results_dir.is_dir():
        p.error(f"결과 폴더가 없습니다: {results_dir}")
    summary = score.score_dir(results_dir, args.version)
    baseline = json.loads(Path(args.baseline).read_text(encoding="utf-8")) if args.baseline else None
    if baseline and baseline.get("version") != summary["version"]:
        p.error(f"평가셋 버전이 달라 비교하지 않습니다: {baseline.get('version')} / {summary['version']}")
    print()
    print(score.format_report(summary, baseline))
    (results_dir / "scores.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    return 1 if stats["failed"] else 0


if __name__ == "__main__":
    sys.exit(main())
