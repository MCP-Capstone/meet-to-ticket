"""평가 실행기 테스트. 실제 cli.py(LLM)를 부르지 않고 가짜 실행기로 명령과 캐시 동작만 본다."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from eval import run_eval
from eval.reference import load_manifest, question_ids

MODELS = {"OPENAI_MODEL": "m1", "OPENAI_REVIEW_MODEL": "m2", "OPENAI_DEDUPE_MODEL": "m3", "OPENAI_FALLBACK_MODEL": ""}
MANIFEST = load_manifest()
REAL_MODEL_IDS = run_eval.model_ids  # 픽스처가 바꾸기 전의 원본


@pytest.fixture
def agent_dir(tmp_path):
    d = tmp_path / "agent"
    (d / "prompts").mkdir(parents=True)
    (d / "prompts" / "extract_system.md").write_text("추출 프롬프트", encoding="utf-8")
    (d / "nodes.py").write_text("x = 1\n", encoding="utf-8")
    return d


@pytest.fixture(autouse=True)
def tmp_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(run_eval, "RESULTS_ROOT", tmp_path / "results")
    monkeypatch.setattr(run_eval, "model_ids", lambda env=None, agent_dir=None: MODELS)


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
    repos = [c[c.index("--repo") + 1] for c in ex.calls if "--repo" in c]
    assert repos == ["meet-to-ticket-eval"] * 2
    assert not any(str(p) for c in ex.calls for p in c if "references" in str(p))  # 정답은 에이전트에 넘기지 않는다
    assert all("/inputs/" in c[2] for c in ex.calls)


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
