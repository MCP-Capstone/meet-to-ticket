"""평가셋 전체를 한 명령으로 돌리고 채점표를 출력한다.

agent 폴더에서 (OPENAI_API_KEY 필요, LLM 비용 발생)
    python -m eval.run_eval --label after --runs 3
    python -m eval.run_eval --label after --plan          # 실행할 명령과 캐시 적중만 보고 끝냄 (비용 없음)
    python -m eval.run_eval --label pr-smoke --smoke      # 소수 스모크 (1회, 결과는 pr-smoke-smoke 폴더)

항상 cli.py --dry-run 으로만 실행한다. GitHub에 발행하지 않으며, 끄는 옵션도 없다.
모든 문항에 --repo <대조 저장소>를 붙여, RAG_ENABLED가 켜져 있어도 실제 저장소를 건드리지 않게 한다.
중복 판단 문항(ev41 ~ ev46)은 mcp-server(8000)와 rag-mcp(8002)가 떠 있고 대조 저장소 시드 이슈가 있어야 한다.

결과 캐시 키: 입력 요약본, 명단, 프롬프트 해시, 모델 ID, 대조 저장소와 시드 목록, agent 설정 환경 변수 전부,
agent 코드 해시, 회차 번호. 키가 같으면 LLM을 다시 부르지 않고 저장된 결과를 복사한다.
실패한 실행과 중복 판단이 건너뛰어진 실행은 캐시하지 않는다.
실패한 실행은 status FAILED 결과 파일로 남겨 채점의 실패 횟수에 반영한다.

한계: cli.py는 agent/.env를 override=True로 읽어 환경 변수를 덮어쓴다. 실행기는 .env를 고칠 수 없으므로
실행 전에 .env를 점검해 경고하고, 백엔드 보고 주소가 있으면 --allow-backend-report 없이는 중단한다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
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
# config.py에서 환경 변수 이름을 열거해 캐시 키에 넣는다. 아래는 키에서 뺄 것
# DRY_RUN은 실행기가 고정, 토큰은 비밀 값이라 키에 넣지 않는다
SETTING_EXCLUDE = frozenset({"DRY_RUN", "BACKEND_INTERNAL_TOKEN"})
ENV_NAME_RE = re.compile(r'_(?:str|int|bool|list)\(\s*"([A-Z][A-Z0-9_]*)"')
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


def effective_env(agent_dir: Path = AGENT_DIR, env: dict[str, str] | None = None) -> dict[str, str]:
    """cli.py가 실제로 보게 될 환경: 프로세스 환경 위에 agent/.env가 덮어쓴다 (load_dotenv override=True)."""
    merged = dict(os.environ if env is None else env)
    dotenv = agent_dir / ".env"
    if dotenv.is_file():
        try:
            from dotenv import dotenv_values  # noqa: PLC0415

            merged.update({k: v for k, v in dotenv_values(dotenv).items() if v is not None})
        except ImportError:
            pass
    return merged


def setting_env_names(agent_dir: Path = AGENT_DIR) -> list[str]:
    """agent/config.py가 읽는 환경 변수 이름 전부 (모델 ID와 제외 목록 빼고)."""
    text = (agent_dir / "config.py").read_text(encoding="utf-8")
    names = set(ENV_NAME_RE.findall(text)) - SETTING_EXCLUDE - set(MODEL_ENV)
    return sorted(names)


def model_ids(env: dict[str, str] | None = None, agent_dir: Path | None = None) -> dict[str, str]:
    """실행에 쓰일 모델 ID. 기본값은 config.py와 같은 값을 쓰도록 설정을 읽고, 못 읽으면 환경 변수 값."""
    agent_dir = agent_dir or AGENT_DIR
    env = effective_env(agent_dir, env)
    if agent_dir.resolve() == AGENT_DIR.resolve():
        try:
            if str(AGENT_DIR) not in sys.path:
                sys.path.insert(0, str(AGENT_DIR))
            from config import Settings  # noqa: PLC0415

            d = Settings()
            defaults = {"OPENAI_MODEL": d.openai_model, "OPENAI_REVIEW_MODEL": d.review_model,
                        "OPENAI_DEDUPE_MODEL": d.dedupe_model, "OPENAI_FALLBACK_MODEL": d.fallback_model}
            return {k: (env.get(k) or "").strip() or defaults[k] for k in MODEL_ENV}
        except Exception:  # noqa: BLE001 - 설정을 못 읽으면 환경 변수 값으로 키를 만든다
            pass
    return {k: (env.get(k) or "").strip() for k in MODEL_ENV}


def settings_env(agent_dir: Path = AGENT_DIR, env: dict[str, str] | None = None) -> dict[str, str]:
    env = effective_env(agent_dir, env)
    return {k: (env.get(k) or "").strip() for k in setting_env_names(agent_dir)}


def env_file_findings(agent_dir: Path = AGENT_DIR, env: dict[str, str] | None = None) -> list[tuple[str, str]]:
    """실행 전 점검. (block|warn, 메시지) 목록. 비밀 값은 출력하지 않는다."""
    env = effective_env(agent_dir, env)
    out = []
    if (env.get("BACKEND_BASE_URL") or "").strip():
        out.append(("block", "BACKEND_BASE_URL이 설정되어 평가 실행의 진행/결과가 백엔드에 보고될 수 있습니다"))
    if (env.get("PUBLISH_ON_RUN") or "").strip().lower() in {"1", "true", "yes", "y", "on"}:
        out.append(("warn", "PUBLISH_ON_RUN=true: 결과가 Phase 1 방식(검수 후 발행 흐름)으로 달라집니다 (--dry-run이라 발행은 안 됨)"))
    if (env.get("RAG_ENABLED") or "").strip().lower() in {"1", "true", "yes", "y", "on"}:
        out.append(("warn", "RAG_ENABLED=true: 추출 문항도 대조 저장소 기준으로 중복 판단을 합니다 (baseline은 꺼서 측정)"))
    return out


def cache_key(input_path: Path, members_path: Path, repo: str | None, prompts: str, models: dict[str, str],
              code: str, run: int, settings: dict[str, str] | None = None, seed: str = "") -> str:
    return _sha(input_path.read_bytes(), members_path.read_bytes(), (repo or "").encode(), prompts.encode(),
                json.dumps(models, sort_keys=True).encode(), code.encode(), str(run).encode(),
                json.dumps(settings or {}, sort_keys=True).encode(), seed.encode())[:24]


def reusable(result: dict[str, Any], job: dict[str, Any]) -> bool:
    """저장해 두고 다시 써도 되는 결과인가. 실패, 중복 판단 문항에서 판단이 건너뛰어진 실행은 아니다."""
    if result.get("status") != "SUCCEEDED":
        return False
    if job.get("dedupe"):
        for it in result.get("items") or []:
            cj = it.get("contentJson") or {}
            if cj.get("publish_target", "GITHUB") == "SLACK":
                continue
            d = cj.get("dedupe")
            if not d or d.get("skipped") or not d.get("relation"):
                return False
    return True


def read_result(path: Path) -> dict[str, Any] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) else None


def atomic_copy(src: Path, dst: Path) -> None:
    """임시 파일에 복사한 뒤 바꿔치기해, 중단돼도 잘린 파일이 남지 않게 한다."""
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_name(dst.name + ".tmp")
    tmp.write_bytes(src.read_bytes())
    os.replace(tmp, dst)


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
    settings = settings_env(agent_dir)
    seed = _sha((ds / "dedupe_seed_issues.md").read_bytes())[:16]
    jobs = []
    for q in questions:
        for n in range(1, runs + 1):
            inp, members = ds / "inputs" / f"{q}_summary.md", ds / "members.json"
            out = results_dir / f"{q}_r{n}.json"
            key = cache_key(inp, members, dedupe_repo, prompts, models, code, n, settings, seed)
            jobs.append({"question": q, "run": n, "input": inp, "members": members, "repo": dedupe_repo,
                         "dedupe": q in dedupe, "out": out, "key": key,
                         "cached": (CACHE_DIR / f"{key}.json").exists()})
    return jobs


def existing_reusable(job: dict[str, Any]) -> bool:
    """결과 폴더에 이미 있는 파일을 그대로 써도 되는가: JSON, status, 캐시 키, 판단 건너뜀 여부."""
    out: Path = job["out"]
    keyfile = out.with_suffix(".key")
    if not out.exists() or not keyfile.exists():
        return False
    result = read_result(out)
    return (result is not None and keyfile.read_text(encoding="utf-8").strip() == job["key"]
            and reusable(result, job))


def failure_result(code: int, why: str) -> dict[str, Any]:
    """실행이 실패했을 때 남기는 결과. 채점이 실패 횟수로 센다."""
    return {"status": "FAILED", "stage": "EVAL_RUNNER", "errorCode": "EVAL_EXEC_FAILED",
            "errorMessage": f"{why} (종료 코드 {code})", "items": [], "summary": {}, "metrics": {}}


def prune_stale_runs(results_dir: Path, questions: list[str], runs: int) -> list[str]:
    """이번 실행의 회차(1..runs) 밖에 있는 이전 결과를 지워, 채점에 섞이지 않게 한다."""
    removed = []
    for q in questions:
        for path in sorted(results_dir.glob(f"{q}_r*.json")):
            m = re.fullmatch(re.escape(q) + r"_r(\d+)\.json", path.name)
            if m and int(m.group(1)) > runs:
                path.unlink()
                path.with_suffix(".key").unlink(missing_ok=True)
                removed.append(path.name)
    return removed


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
        keyfile = out.with_suffix(".key")
        if not force and existing_reusable(j):
            stats["skipped"].append(name)
            continue
        out.unlink(missing_ok=True)
        keyfile.unlink(missing_ok=True)
        if use_cache and not force and cached.exists():
            hit = read_result(cached)
            if hit is not None and reusable(hit, j):
                atomic_copy(cached, out)
                keyfile.write_text(j["key"], encoding="utf-8")
                stats["cached"].append(name)
                log(f"[캐시] {name}")
                continue
            cached.unlink(missing_ok=True)  # 잘렸거나 쓸 수 없는 캐시는 버리고 다시 실행
            log(f"[캐시 폐기] {name}")
        log(f"[실행] {name}")
        code = executor(build_command(j["input"], j["members"], out, j["repo"]), agent_dir, env)
        result = read_result(out) if out.exists() else None
        if code != 0 or result is None or result.get("status") != "SUCCEEDED":
            if code != 0 or result is None:
                why = "실행 실패" if result is None else "비정상 종료"
                out.write_text(json.dumps(failure_result(code, why), ensure_ascii=False), encoding="utf-8")
            stats["failed"].append(name)
            log(f"[실패] {name} (종료 코드 {code}, status {(result or {}).get('status')})")
            continue
        stats["ran"].append(name)
        keyfile.write_text(j["key"], encoding="utf-8")
        if reusable(result, j):
            atomic_copy(out, cached)
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


def load_baseline(path: str | None, version: str) -> dict[str, Any] | None:
    """--baseline 사전 검증. 실행(비용) 전에 부른다. 문제가 있으면 SystemExit."""
    if not path:
        return None
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise SystemExit(f"--baseline 파일을 읽을 수 없습니다: {path} ({exc})") from exc
    if not isinstance(data, dict) or data.get("version") != version:
        got = data.get("version") if isinstance(data, dict) else None
        raise SystemExit(f"평가셋 버전이 달라 비교하지 않습니다: {got} / {version}")
    return data


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
    p.add_argument("--allow-backend-report", action="store_true",
                   help=".env에 BACKEND_BASE_URL이 있어도 실행 (평가 실행이 백엔드에 보고될 수 있음)")
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
    baseline = load_baseline(args.baseline, args.version)  # 비용이 들기 전에 확인
    manifest = load_manifest(args.version)
    ids = question_ids(args.version)
    only = [q.strip() for q in args.questions.split(",") if q.strip()] if args.questions else None
    questions = select_questions(ids, set(manifest["dedupe"]), group=args.group, only=only, smoke=args.smoke)
    runs = 1 if args.smoke else args.runs
    # 스모크는 일부 문항만 있으므로 전체 실행과 폴더를 분리한다
    results_dir = RESULTS_ROOT / args.version / (f"{args.label}-smoke" if args.smoke else args.label)

    stats: dict[str, list[str]] = {"failed": []}
    if not args.score_only:
        findings = env_file_findings(agent_dir)
        for level, msg in findings:
            print(f"[{'중단' if level == 'block' and not args.allow_backend_report else '경고'}] {msg}")
        if not args.plan and any(lv == "block" for lv, _ in findings) and not args.allow_backend_report:
            p.error("agent/.env의 BACKEND_BASE_URL을 비우거나 --allow-backend-report를 주세요 "
                    "(cli.py가 .env를 환경 변수보다 우선해 읽어서 실행기가 막을 수 없습니다)")
        jobs = plan_jobs(questions, runs, results_dir, args.version, manifest["dedupe"], manifest["dedupe_repo"],
                         agent_dir=agent_dir)
        print(f"평가셋 {args.version}, 문항 {len(questions)}개 x {runs}회 = {len(jobs)}건, 결과 {results_dir}")
        print(f"프롬프트 {prompt_hash(agent_dir)} 코드 {code_hash(agent_dir)} "
              f"(DRY-RUN 고정, GitHub 쓰기 없음, --repo {manifest['dedupe_repo']})")
        if any(j["dedupe"] for j in jobs):
            print("중복 판단 문항 포함: mcp-server(8000), rag-mcp(8002), 대조 저장소 시드 이슈가 필요합니다")
        if args.plan:
            for j in jobs:
                if not args.force and existing_reusable(j):
                    tag = "재사용"
                elif not args.no_cache and not args.force and j["cached"]:
                    tag = "캐시"
                else:
                    tag = "실행"
                print(f"  [{tag}] {j['question']} r{j['run']}")
            if jobs:
                print("명령 예: " + " ".join(["python", *build_command(jobs[0]["input"], jobs[0]["members"],
                                                                      jobs[0]["out"], jobs[0]["repo"])[1:]]))
            return 0
        removed = prune_stale_runs(results_dir, questions, runs) if results_dir.is_dir() else []
        if removed:
            print(f"이번 회차 범위({runs}회) 밖의 이전 결과 {len(removed)}개를 지웠습니다: {', '.join(removed)}")
        stats = run_jobs(jobs, executor, use_cache=not args.no_cache, force=args.force, agent_dir=agent_dir)
        print(f"\n실행 {len(stats['ran'])}, 캐시 {len(stats['cached'])}, 기존 결과 {len(stats['skipped'])}, 실패 {len(stats['failed'])}")
        if stats["failed"]:
            print("실패: " + ", ".join(stats["failed"]))

    if not results_dir.is_dir():
        p.error(f"결과 폴더가 없습니다: {results_dir}")
    summary = score.score_dir(results_dir, args.version)
    print()
    print(score.format_report(summary, baseline))
    (results_dir / "scores.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    failed_runs = sum((summary.get(g) or {}).get("failed_runs", 0) for g in ("extract", "dedupe"))
    return 1 if stats["failed"] or failed_runs or summary.get("corrupt") else 0


if __name__ == "__main__":
    sys.exit(main())
