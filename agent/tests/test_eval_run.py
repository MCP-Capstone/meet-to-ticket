"""평가 실행기 테스트. 실제 cli.py(LLM)를 부르지 않고 가짜 실행기로 명령과 캐시 동작만 본다."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

from eval import run_eval
from eval.reference import load_manifest, question_ids

MODELS = {"OPENAI_MODEL": "m1", "OPENAI_REVIEW_MODEL": "m2", "OPENAI_DEDUPE_MODEL": "m3", "OPENAI_FALLBACK_MODEL": ""}
MANIFEST = load_manifest()
REAL_MODEL_IDS = run_eval.model_ids  # 픽스처가 바꾸기 전의 원본
REAL_FINDINGS = run_eval.env_file_findings


@pytest.fixture
def agent_dir(tmp_path):
    d = tmp_path / "agent"
    (d / "prompts").mkdir(parents=True)
    (d / "prompts" / "extract_system.md").write_text("추출 프롬프트", encoding="utf-8")
    (d / "nodes.py").write_text("x = 1\n", encoding="utf-8")
    (d / "config.py").write_text((run_eval.AGENT_DIR / "config.py").read_text(encoding="utf-8"), encoding="utf-8")
    return d


@pytest.fixture(autouse=True)
def tmp_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(run_eval, "RESULTS_ROOT", tmp_path / "results")
    monkeypatch.setattr(run_eval, "model_ids", lambda env=None, agent_dir=None: MODELS)
    monkeypatch.setattr(run_eval, "env_file_findings", lambda agent_dir=None, env=None: [])  # 개발자 .env에 영향받지 않게


class FakeExecutor:
    def __init__(self, status="SUCCEEDED", code=0):
        self.calls: list[list[str]] = []
        self.envs: list[dict[str, str]] = []
        self.status, self.code = status, code

    def __call__(self, cmd, cwd, env):
        self.calls.append(cmd)
        self.envs.append(env)
        out = Path(cmd[cmd.index("--out") + 1])
        out.write_text(json.dumps({"status": self.status, "items": [], "metrics": {"durationMs": 5}}), encoding="utf-8")
        return self.code


def jobs_for(tmp_path, agent_dir, questions=("ev21_zero_action", "ev41_same_open"), runs=2):
    return run_eval.plan_jobs(list(questions), runs, tmp_path / "out", "v2.1", MANIFEST["dedupe"],
                              MANIFEST["dedupe_repo"], agent_dir=agent_dir, models=MODELS)


def test_command_always_dry_run_and_dedupe_uses_eval_repo(tmp_path, agent_dir):
    jobs = jobs_for(tmp_path, agent_dir)
    ex = FakeExecutor()
    run_eval.run_jobs(jobs, ex, agent_dir=agent_dir)
    assert len(ex.calls) == 4
    assert all("--dry-run" in c for c in ex.calls)
    assert all(e["DRY_RUN"] == "true" for e in ex.envs)
    # 추출 문항도 실제 저장소가 아니라 대조 저장소를 명시한다 (RAG_ENABLED가 켜져 있어도 안전)
    repos = [c[c.index("--repo") + 1] for c in ex.calls]
    assert repos == ["meet-to-ticket-eval"] * 4
    assert not any(str(p) for c in ex.calls for p in c if "references" in str(p))  # 정답은 에이전트에 넘기지 않는다
    assert all(Path(c[2]).parent.name == "inputs" for c in ex.calls)


def test_cache_hit_skips_second_execution(tmp_path, agent_dir):
    ex = FakeExecutor()
    stats = run_eval.run_jobs(jobs_for(tmp_path, agent_dir), ex, agent_dir=agent_dir)
    assert len(stats["ran"]) == 4 and not stats["cached"]
    # 다른 결과 폴더에서 같은 키를 다시 돌리면 캐시를 쓴다
    again = run_eval.plan_jobs(["ev21_zero_action", "ev41_same_open"], 2, tmp_path / "out2", "v2.1", MANIFEST["dedupe"],
                               MANIFEST["dedupe_repo"], agent_dir=agent_dir, models=MODELS)
    assert all(j["cached"] for j in again)
    ex2 = FakeExecutor()
    stats = run_eval.run_jobs(again, ex2, agent_dir=agent_dir)
    assert ex2.calls == [] and len(stats["cached"]) == 4
    assert (tmp_path / "out2" / "ev21_zero_action_r1.json").exists()


def test_existing_result_is_resumed_not_rerun(tmp_path, agent_dir):
    jobs = jobs_for(tmp_path, agent_dir, runs=1)
    run_eval.run_jobs(jobs, FakeExecutor(), agent_dir=agent_dir)
    ex = FakeExecutor()
    stats = run_eval.run_jobs(jobs, ex, agent_dir=agent_dir)
    assert ex.calls == [] and len(stats["skipped"]) == 2
    run_eval.run_jobs(jobs, ex, force=True, use_cache=False, agent_dir=agent_dir)
    assert len(ex.calls) == 2


def test_failed_runs_are_not_cached(tmp_path, agent_dir):
    jobs = jobs_for(tmp_path, agent_dir, runs=1)
    stats = run_eval.run_jobs(jobs, FakeExecutor(code=1), agent_dir=agent_dir)
    assert len(stats["failed"]) == 2
    stats = run_eval.run_jobs(jobs_for(tmp_path, agent_dir, runs=1), FakeExecutor(status="FAILED"), force=True,
                              agent_dir=agent_dir)
    assert not list((tmp_path / "cache").glob("*.json"))


def test_cache_key_changes_with_prompt_model_input_code_and_run(tmp_path, agent_dir):
    def keys(**kw):
        return [j["key"] for j in jobs_for(tmp_path, agent_dir, questions=("ev11_relative_due",), runs=2)] if not kw else \
            [j["key"] for j in run_eval.plan_jobs(["ev11_relative_due"], 2, tmp_path / "o", "v2.1", MANIFEST["dedupe"],
                                                  MANIFEST["dedupe_repo"], agent_dir=agent_dir, models=kw.get("models", MODELS))]

    base = keys()
    assert len(set(base)) == 2  # 회차마다 다른 키
    assert keys() == base
    assert keys(models={**MODELS, "OPENAI_MODEL": "other"}) != base
    (agent_dir / "prompts" / "extract_system.md").write_text("바뀐 프롬프트", encoding="utf-8")
    after_prompt = keys()
    assert after_prompt != base
    (agent_dir / "nodes.py").write_text("x = 2\n", encoding="utf-8")
    assert keys() != after_prompt


def test_select_questions():
    ids = question_ids()
    dedupe = set(MANIFEST["dedupe"])
    assert len(run_eval.select_questions(ids, dedupe, group="all", only=None, smoke=False)) == 30
    assert len(run_eval.select_questions(ids, dedupe, group="dedupe", only=None, smoke=False)) == 6
    assert len(run_eval.select_questions(ids, dedupe, group="extract", only=None, smoke=False)) == 24
    assert run_eval.select_questions(ids, dedupe, group="all", only=None, smoke=True) == sorted(run_eval.SMOKE)
    with pytest.raises(SystemExit):
        run_eval.select_questions(ids, dedupe, group="all", only=["nope"], smoke=False)


def test_plan_makes_no_calls(capsys):
    ex = FakeExecutor()
    assert run_eval.main(["--plan", "--label", "t"], executor=ex) == 0
    assert ex.calls == []
    out = capsys.readouterr().out
    assert "DRY-RUN" in out and "--dry-run" in out and "--repo meet-to-ticket-eval" in out


def test_agent_dir_option_runs_other_checkout(tmp_path, agent_dir):
    ex = FakeExecutor()
    cwds = []

    def spy(cmd, cwd, env):
        cwds.append(cwd)
        return ex(cmd, cwd, env)

    with pytest.raises(SystemExit):  # cli.py 없는 폴더는 거절
        run_eval.main(["--smoke", "--label", "x", "--agent-dir", str(agent_dir)], executor=spy)
    (agent_dir / "cli.py").write_text("", encoding="utf-8")
    run_eval.main(["--smoke", "--label", "x", "--agent-dir", str(agent_dir)], executor=spy)
    assert set(cwds) == {agent_dir.resolve()}


def test_model_ids_for_other_checkout_use_env_only(tmp_path):
    got = REAL_MODEL_IDS({"OPENAI_MODEL": "x"}, agent_dir=tmp_path)
    assert got["OPENAI_MODEL"] == "x" and got["OPENAI_REVIEW_MODEL"] == ""


def test_main_runs_and_prints_table(tmp_path, capsys):
    ex = FakeExecutor()
    assert run_eval.main(["--smoke", "--label", "s"], executor=ex) == 0
    assert len(ex.calls) == len(run_eval.SMOKE)
    out = capsys.readouterr().out
    assert "추출 정밀도" in out and "평가셋 v2.1" in out
    assert (run_eval.RESULTS_ROOT / "v2.1" / "s-smoke" / "scores.json").exists()
    assert not (run_eval.RESULTS_ROOT / "v2.1" / "s").exists()  # 스모크는 전체 실행 폴더와 분리
    # 모든 실행이 실패하면 종료 코드 1
    assert run_eval.main(["--smoke", "--label", "f", "--no-cache"], executor=FakeExecutor(code=2)) == 1


def test_questions_option_strips_spaces():
    ex = FakeExecutor()
    run_eval.main(["--questions", "ev11_relative_due, ev21_zero_action", "--runs", "1", "--label", "q"], executor=ex)
    assert len(ex.calls) == 2


class DedupeSkippedExecutor(FakeExecutor):
    """중복 판단이 건너뛰어진 결과를 만든다."""

    def __call__(self, cmd, cwd, env):
        self.calls.append(cmd)
        out = Path(cmd[cmd.index("--out") + 1])
        item = {"title": "t", "contentJson": {"dedupe": {"relation": None, "issue_number": None, "skipped": "timeout"}}}
        out.write_text(json.dumps({"status": "SUCCEEDED", "items": [item]}), encoding="utf-8")
        return 0


def test_skipped_dedupe_is_neither_cached_nor_reused(tmp_path, agent_dir):
    jobs = jobs_for(tmp_path, agent_dir, questions=("ev41_same_open",), runs=1)
    run_eval.run_jobs(jobs, DedupeSkippedExecutor(), agent_dir=agent_dir)
    assert not list((tmp_path / "cache").glob("*.json"))
    ex = DedupeSkippedExecutor()
    run_eval.run_jobs(jobs, ex, agent_dir=agent_dir)
    assert len(ex.calls) == 1  # 건너뛴 판단은 다음 실행에서 다시 시도한다


def test_existing_result_is_validated_before_skip(tmp_path, agent_dir):
    jobs = jobs_for(tmp_path, agent_dir, questions=("ev21_zero_action",), runs=1)
    out = jobs[0]["out"]
    run_eval.run_jobs(jobs, FakeExecutor(), agent_dir=agent_dir)
    for broken in ('{"status": "SUC', json.dumps({"status": "FAILED", "items": []})):
        out.write_text(broken, encoding="utf-8")
        ex = FakeExecutor()
        run_eval.run_jobs(jobs, ex, use_cache=False, agent_dir=agent_dir)
        assert len(ex.calls) == 1, broken
    # 다른 캐시 키(코드나 프롬프트가 바뀜)로 만든 결과는 다시 실행한다
    out.with_suffix(".key").write_text("old", encoding="utf-8")
    ex = FakeExecutor()
    run_eval.run_jobs(jobs, ex, use_cache=False, agent_dir=agent_dir)
    assert len(ex.calls) == 1
    # 키 파일이 없는 결과(출처 불명)도 재사용하지 않는다
    out.with_suffix(".key").unlink()
    ex = FakeExecutor()
    run_eval.run_jobs(jobs, ex, use_cache=False, agent_dir=agent_dir)
    assert len(ex.calls) == 1


def test_cache_key_includes_settings_and_seed(tmp_path, agent_dir, monkeypatch):
    qs = ("ev41_same_open", "ev21_zero_action")
    base = [j["key"] for j in jobs_for(tmp_path, agent_dir, questions=qs, runs=1)]
    monkeypatch.setenv("DEDUPE_MAX_TOOL_CALLS", "9")
    changed = [j["key"] for j in jobs_for(tmp_path, agent_dir, questions=qs, runs=1)]
    assert all(a != b for a, b in zip(base, changed))
    ds = run_eval.dataset_dir("v2.1")
    args = (ds / "inputs" / "ev41_same_open_summary.md", ds / "members.json", "r", "p", MODELS, "c", 1, {})
    assert run_eval.cache_key(*args, "seed-a") != run_eval.cache_key(*args, "seed-b")


# ---------------------------------------------------------------- 설정 키, 실패 기록, 회차, 캐시 손상, 사전 검증


def test_cache_key_covers_every_env_var_config_reads():
    text = (run_eval.AGENT_DIR / "config.py").read_text(encoding="utf-8")
    read = set(run_eval.ENV_NAME_RE.findall(text))
    covered = set(run_eval.setting_env_names()) | run_eval.SETTING_EXCLUDE | set(run_eval.MODEL_ENV)
    assert read == covered  # config.py에 변수가 늘면 자동으로 키에 들어간다
    for name in ("PUBLISH_ON_RUN", "CONDITIONAL_AS_NEEDS_CHECK", "FALLBACK_PUBLISH", "MCP_SERVER_URL", "RAG_MCP_URL",
                 "BACKEND_BASE_URL", "RAG_ENABLED", "DEDUPE_MAX_TOOL_CALLS"):
        assert name in run_eval.setting_env_names(), name
    assert "BACKEND_INTERNAL_TOKEN" not in run_eval.setting_env_names()  # 비밀 값은 키에 넣지 않는다


@pytest.mark.parametrize("name,value", [("PUBLISH_ON_RUN", "true"), ("FALLBACK_PUBLISH", "false"),
                                         ("CONDITIONAL_AS_NEEDS_CHECK", "false"), ("MCP_SERVER_URL", "http://x/mcp")])
def test_cache_key_changes_with_each_setting(tmp_path, agent_dir, monkeypatch, name, value):
    base = jobs_for(tmp_path, agent_dir, questions=("ev11_relative_due",), runs=1)[0]["key"]
    monkeypatch.setenv(name, value)
    assert jobs_for(tmp_path, agent_dir, questions=("ev11_relative_due",), runs=1)[0]["key"] != base


def test_agent_dotenv_overrides_process_env_in_key(tmp_path, agent_dir, monkeypatch):
    monkeypatch.setenv("PUBLISH_ON_RUN", "false")
    base = jobs_for(tmp_path, agent_dir, questions=("ev11_relative_due",), runs=1)[0]["key"]
    (agent_dir / ".env").write_text("PUBLISH_ON_RUN=true\n", encoding="utf-8")  # cli.py는 .env를 우선해 읽는다
    assert jobs_for(tmp_path, agent_dir, questions=("ev11_relative_due",), runs=1)[0]["key"] != base


def test_failed_runs_are_recorded_and_counted(tmp_path, agent_dir):
    from eval import score

    jobs = jobs_for(tmp_path, agent_dir, questions=("ev21_zero_action",), runs=2)
    stats = run_eval.run_jobs(jobs, FakeExecutor(code=3), agent_dir=agent_dir)
    assert len(stats["failed"]) == 2
    assert all(j["out"].exists() for j in jobs)  # 지우지 않고 FAILED 결과로 남긴다
    assert score.score_dir(tmp_path / "out")["extract"]["failed_runs"] == 2


def test_exit_zero_with_failed_status_counts_as_failed():
    ex = FakeExecutor(status="FAILED", code=0)
    assert run_eval.main(["--questions", "ev21_zero_action", "--runs", "1", "--label", "z"], executor=ex) == 1


def test_lower_runs_prunes_stale_run_files():
    run_eval.main(["--questions", "ev21_zero_action", "--runs", "3", "--label", "p"], executor=FakeExecutor())
    d = run_eval.RESULTS_ROOT / "v2.1" / "p"
    assert len(list(d.glob("ev21_zero_action_r*.json"))) == 3
    run_eval.main(["--questions", "ev21_zero_action", "--runs", "1", "--label", "p"], executor=FakeExecutor())
    assert [f.name for f in d.glob("ev21_zero_action_r*.json")] == ["ev21_zero_action_r1.json"]
    assert json.loads((d / "scores.json").read_text(encoding="utf-8"))["extract"]["runs"] == 1


def test_truncated_cache_is_discarded_and_rerun(tmp_path, agent_dir):
    jobs = jobs_for(tmp_path, agent_dir, questions=("ev21_zero_action",), runs=1)
    cache = tmp_path / "cache"
    cache.mkdir()
    (cache / f"{jobs[0]['key']}.json").write_text('{"status": "SUCC', encoding="utf-8")
    ex = FakeExecutor()
    stats = run_eval.run_jobs(jobs, ex, agent_dir=agent_dir)
    assert len(ex.calls) == 1 and stats["cached"] == []
    assert json.loads((cache / f"{jobs[0]['key']}.json").read_text(encoding="utf-8"))["status"] == "SUCCEEDED"
    assert not list(cache.glob("*.tmp")) and not list((tmp_path / "out").glob("*.tmp"))


def test_atomic_copy_replaces_without_leftovers(tmp_path):
    src, dst = tmp_path / "a.json", tmp_path / "sub" / "b.json"
    src.write_text("{}", encoding="utf-8")
    run_eval.atomic_copy(src, dst)
    assert dst.read_text(encoding="utf-8") == "{}" and not list(dst.parent.glob("*.tmp"))


def test_corrupt_result_file_is_reported_not_fatal(tmp_path):
    from eval import score

    d = tmp_path / "res"
    d.mkdir()
    (d / "ev21_zero_action_r1.json").write_text('{"status": "SUC', encoding="utf-8")
    summary = score.score_dir(d)
    assert summary["extract"]["failed_runs"] == 1
    assert "ev21_zero_action_r1.json" in summary["corrupt"][0]
    assert "읽을 수 없는 결과 파일" in score.format_report(summary)


def test_bad_baseline_stops_before_any_execution(tmp_path):
    ex = FakeExecutor()
    with pytest.raises(SystemExit):
        run_eval.main(["--smoke", "--label", "b", "--baseline", str(tmp_path / "nope.json")], executor=ex)
    bad = tmp_path / "old.json"
    bad.write_text(json.dumps({"version": "v1"}), encoding="utf-8")
    with pytest.raises(SystemExit):
        run_eval.main(["--smoke", "--label", "b", "--baseline", str(bad)], executor=ex)
    assert ex.calls == []


def test_env_findings_block_backend_reporting(tmp_path, agent_dir, monkeypatch):
    monkeypatch.setattr(run_eval, "env_file_findings", REAL_FINDINGS)
    (agent_dir / "cli.py").write_text("", encoding="utf-8")
    (agent_dir / ".env").write_text("BACKEND_BASE_URL=http://backend\nRAG_ENABLED=true\n", encoding="utf-8")
    assert {lv for lv, _ in REAL_FINDINGS(agent_dir, {})} == {"block", "warn"}
    ex = FakeExecutor()
    with pytest.raises(SystemExit):
        run_eval.main(["--smoke", "--label", "e", "--agent-dir", str(agent_dir)], executor=ex)
    assert ex.calls == []
    run_eval.main(["--smoke", "--label", "e", "--agent-dir", str(agent_dir), "--allow-backend-report"], executor=ex)
    assert len(ex.calls) == len(run_eval.SMOKE)


def test_plan_shows_real_reuse_state(capsys):
    argv = ["--questions", "ev21_zero_action", "--runs", "1", "--label", "pl"]
    run_eval.main(argv, executor=FakeExecutor())
    capsys.readouterr()
    run_eval.main([*argv, "--plan"])
    assert "[재사용] ev21_zero_action r1" in capsys.readouterr().out
    out = run_eval.RESULTS_ROOT / "v2.1" / "pl" / "ev21_zero_action_r1.json"
    out.with_suffix(".key").write_text("stale", encoding="utf-8")
    run_eval.main([*argv, "--plan"])
    assert "[캐시] ev21_zero_action r1" in capsys.readouterr().out  # 키가 달라 재사용 불가, 캐시에서 복사
    run_eval.main([*argv, "--plan", "--no-cache"])
    assert "[실행] ev21_zero_action r1" in capsys.readouterr().out


# --- 회차별 시간 제한 (--run-timeout) ---

def _spy_subprocess_run(monkeypatch, returncode=0):
    """subprocess.run을 가짜로 바꿔 넘겨받은 timeout만 기록한다. 실제 프로세스는 만들지 않는다."""
    seen: list[object] = []

    def fake_run(cmd, **kwargs):
        seen.append(kwargs.get("timeout", "missing"))
        return subprocess.CompletedProcess(cmd, returncode)

    monkeypatch.setattr(run_eval.subprocess, "run", fake_run)
    return seen


def test_executor_times_out_kills_child_and_returns_124(tmp_path, capsys):
    pidfile = tmp_path / "child.pid"
    code = f"import os, time; open({str(pidfile)!r}, 'w').write(str(os.getpid())); time.sleep(60)"
    start = time.monotonic()
    rc = run_eval.default_executor([sys.executable, "-c", code], tmp_path, dict(os.environ), timeout=2)
    assert rc == run_eval.TIMEOUT_EXIT_CODE == 124
    assert time.monotonic() - start < 30  # 60초를 기다리지 않는다
    assert "[시간 초과] 회차 제한 2초" in capsys.readouterr().err
    if sys.platform != "win32":  # Windows의 os.kill(pid, 0)은 확인이 아니라 프로세스 종료라 쓸 수 없다
        with pytest.raises(ProcessLookupError):  # 자식 프로세스가 남아 있지 않다
            os.kill(int(pidfile.read_text()), 0)


def test_executor_passes_through_exit_code_within_limit(tmp_path, capsys):
    env = dict(os.environ)
    assert run_eval.default_executor([sys.executable, "-c", "pass"], tmp_path, env, timeout=60) == 0
    assert run_eval.default_executor([sys.executable, "-c", "raise SystemExit(3)"], tmp_path, env, timeout=60) == 3
    assert "시간 초과" not in capsys.readouterr().err


def test_executor_timeout_default_and_disabled_values(tmp_path, monkeypatch):
    seen = _spy_subprocess_run(monkeypatch)
    env = dict(os.environ)
    run_eval.default_executor(["x"], tmp_path, env)
    run_eval.default_executor(["x"], tmp_path, env, timeout=45)
    run_eval.default_executor(["x"], tmp_path, env, timeout=0)
    run_eval.default_executor(["x"], tmp_path, env, timeout=-1)
    run_eval.default_executor(["x"], tmp_path, env, timeout=None)
    assert run_eval.DEFAULT_RUN_TIMEOUT == 300.0
    assert seen == [300.0, 45, None, None, None]


def test_run_timeout_option_default_and_off(monkeypatch):
    seen = _spy_subprocess_run(monkeypatch, returncode=1)
    base = ["--questions", "ev21_zero_action", "--runs", "1", "--no-cache", "--force"]
    for extra in ([], ["--run-timeout", "45"], ["--run-timeout", "0"], ["--run-timeout", "-5"]):
        assert run_eval.main([*base, "--label", "to", *extra]) == 1  # 가짜 실행은 결과를 안 만들어 실패로 센다
    assert seen == [300.0, 45.0, None, None]


def test_timed_out_run_is_failed_and_other_runs_continue(tmp_path, agent_dir):
    class HangsOnce(FakeExecutor):
        def __call__(self, cmd, cwd, env):
            if "ev21_zero_action_r1" in cmd[cmd.index("--out") + 1]:
                self.calls.append(cmd)
                return run_eval.TIMEOUT_EXIT_CODE  # 시간 초과: 결과 파일을 못 만들고 끝남
            return super().__call__(cmd, cwd, env)

    logs: list[str] = []
    jobs = jobs_for(tmp_path, agent_dir)  # 2문항 x 2회 = 4건, 멈춘 회차는 첫 번째
    ex = HangsOnce()
    stats = run_eval.run_jobs(jobs, ex, agent_dir=agent_dir, log=logs.append)
    assert len(ex.calls) == 4  # 멈춘 회차 뒤의 회차도 모두 실행했다
    assert stats["failed"] == ["ev21_zero_action_r1"]
    assert stats["ran"] == ["ev21_zero_action_r2", "ev41_same_open_r1", "ev41_same_open_r2"]
    failed = json.loads((tmp_path / "out" / "ev21_zero_action_r1.json").read_text(encoding="utf-8"))
    assert failed["status"] == "FAILED" and failed["errorCode"] == "EVAL_EXEC_FAILED"
    assert failed["errorMessage"] == "시간 초과 (종료 코드 124)"
    assert "[실패] ev21_zero_action_r1 (종료 코드 124, 시간 초과, status None)" in logs
    assert len(list((tmp_path / "cache").glob("*.json"))) == 3  # 시간 초과 회차는 캐시하지 않는다

    # 같은 명령을 다시 실행하면 온전한 3건은 건너뛰고 시간 초과 회차만 다시 돈다
    retry = FakeExecutor()
    stats = run_eval.run_jobs(jobs, retry, agent_dir=agent_dir)
    assert len(retry.calls) == 1 and "ev21_zero_action_r1.json" in retry.calls[0][retry.calls[0].index("--out") + 1]
    assert len(stats["skipped"]) == 3 and stats["ran"] == ["ev21_zero_action_r1"] and not stats["failed"]


def test_timeout_makes_main_exit_one_and_counts_failure(capsys):
    class AlwaysTimesOut(FakeExecutor):
        def __call__(self, cmd, cwd, env):
            self.calls.append(cmd)
            return run_eval.TIMEOUT_EXIT_CODE

    ex = AlwaysTimesOut()
    argv = ["--questions", "ev11_relative_due,ev21_zero_action", "--runs", "1", "--label", "to2"]
    assert run_eval.main(argv, executor=ex) == 1
    assert len(ex.calls) == 2  # 첫 회차가 시간 초과여도 두 번째 회차를 실행한다
    assert "실패 2" in capsys.readouterr().out
