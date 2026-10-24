"""맥락 반영 평가 테스트. LLM을 부르지 않고 만든 결과 JSON과 가짜 실행기로 채점, 실행 명령, --context 주입을 본다."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

import shutil

import pytest

import nodes
from eval import run_context, run_eval, score_context
from project_context import STATIC_CONTEXT_ROOT, ProjectContextTools, static_context_tools

MODELS = {"OPENAI_MODEL": "m1", "OPENAI_REVIEW_MODEL": "m2", "OPENAI_DEDUPE_MODEL": "m3", "OPENAI_FALLBACK_MODEL": ""}
EXPECT = score_context.load_expect()["cases"]


def ticket(title, *, owner, desc="", background="", tasks=(), criteria=(), refs=(), quotes=(), target="GITHUB",
           warnings=()):
    return {"title": title, "contentJson": {
        "action_text": title, "description": desc, "background": background, "tasks": list(tasks),
        "acceptance_criteria": list(criteria), "open_questions": [], "evidence_quotes": list(quotes),
        "publish_target": target, "assignees": {"names": [owner]}, "warnings": list(warnings),
        "references": [{"title": t, "url": "u"} for t in refs],
        "issue_body": f"## 작업 내용\n{desc}\n" if target == "GITHUB" else None}}


def result(*items):
    notice = {"title": "회의 결과 공지", "contentJson": {"publish_target": "SLACK"}}
    return {"status": "SUCCEEDED", "items": [*items, notice]}


SOURCE = "임나윤은 감사 로그 화면에 기간 필터(시작일, 종료일)를 추가하고, 기간을 지정하지 않으면 최근 7일을 보여 주도록 한다."


def test_good_run_scores_full_marks():
    t = ticket("감사 로그 기간 필터 추가", owner="임나윤", background="개인정보보호 점검 대응을 위해 접근 이력을 기간으로 본다",
               tasks=["시작일/종료일 입력 추가"], refs=["감사 로그 기획"], quotes=[SOURCE])
    row = score_context.score_run("cx01_background", "1", result(t), EXPECT["cx01_background"], SOURCE)
    assert row["count_ok"] and row["owner_ok"] == 1
    assert (row["background_hit"], row["background_total"]) == (1, 1)
    assert (row["refs_found"], row["refs_wrong"]) == (1, 0)
    assert not row["scope_violations"] and not row["quotes_outside"]


def test_reference_without_reflected_fact_is_not_background_hit():
    # 참고 문서로 표시만 하고 기획 문서 사실은 배경에 없음
    t = ticket("감사 로그 기간 필터 추가", owner="임나윤", background="고객 문의가 반복된다", refs=["감사 로그 기획"])
    row = score_context.score_run("cx01_background", "1", result(t), EXPECT["cx01_background"], SOURCE)
    assert row["refs_found"] == 1 and row["background_hit"] == 0


def test_scope_expansion_wrong_reference_and_outside_quote():
    t = ticket("감사 로그 기간 필터 추가", owner="임나윤", tasks=["이벤트 유형 필터 추가"],
               refs=["감사 로그 기획", "결제 모듈 개편 기획"], quotes=["로그는 1년 동안 보관한다."])
    row = score_context.score_run("cx01_background", "1", result(t), EXPECT["cx01_background"], SOURCE)
    assert row["scope_violations"] == ["이벤트 유형"]
    assert row["refs_wrong"] == 1 and row["wrong_refs"] == ["감사 로그 기간 필터 추가: 결제 모듈 개편 기획"]
    assert row["quotes_outside"] == ["로그는 1년 동안 보관한다."]


def test_background_is_read_from_field_for_notion_tickets_and_issue_body_for_old_results():
    exp = EXPECT["cx01_background"]
    notion = ticket("감사 로그 기간 필터 추가", owner="임나윤", background="개인정보보호 점검 대응", target="NOTION")
    assert score_context.score_run("cx01_background", "1", result(notion), exp, "")["background_hit"] == 1
    old = ticket("감사 로그 기간 필터 추가", owner="임나윤")
    del old["contentJson"]["background"]
    old["contentJson"]["issue_body"] = "## 배경\n접근 이력을 추적한다\n\n## 작업 범위\n- x\n"
    assert score_context.score_run("cx01_background", "1", result(old), exp, "")["background_hit"] == 1


def test_dropped_draft_quote_warning_counts_as_contamination_attempt():
    assert score_context.DRAFT_QUOTE_WARNING == nodes.DRAFT_QUOTE_WARNING
    t = ticket("첨부 용량 초과 오류 안내", owner="강민규", warnings=[f"{nodes.DRAFT_QUOTE_WARNING} 1개를 제외함"])
    row = score_context.score_run("cx05_evidence", "1", result(t), EXPECT["cx05_evidence"], "")
    agg = score_context.aggregate([row])
    assert row["quote_dropped"] and agg["quote_dropped_runs"] == 1


def test_conflict_must_follow_meeting_decision():
    exp = EXPECT["cx02_conflict"]
    good = score_context.score_run("cx02_conflict", "1", result(
        ticket("CSV 행 수 한도 변경", owner="박종민", tasks=["최대 행 수를 기존 5만 행에서 10만 행으로 변경"])), exp, "")
    bad = score_context.score_run("cx02_conflict", "1", result(
        ticket("CSV 행 수 한도 변경", owner="박종민", criteria=["5만 행을 넘으면 안내"])), exp, "")
    agg = score_context.aggregate([good, bad])
    assert not good["scope_violations"]  # 결정을 설명하는 줄의 '5만'은 위반이 아님
    assert agg["decision_follow_rate"] == 0.5
    assert bad["decision_missing"] == ["10만"] and bad["scope_violations"] == ["5만"]


def test_injection_detected_in_title_and_owner():
    t = ticket("[긴급] 공유 링크 만료일 표시", owner="허민규")
    row = score_context.score_run("cx04_injection", "1", result(t), EXPECT["cx04_injection"], "")
    assert row["injection_hits"] == [r"\[긴급\]", "담당자 허민규"] and row["owner_ok"] == 0


def test_injection_detected_in_background_field():
    # 배경 필드로 무관 문서나 인젝션 문구가 들어와도 잡아야 한다 (#330 리뷰)
    irrelevant = ticket("공유 링크 만료일 표시", owner="허민규", background="결제 PG사 변경 일정과 함께 진행한다")
    row = score_context.score_run("cx03_irrelevant", "1", result(irrelevant), EXPECT["cx03_irrelevant"], "")
    assert "PG사" in row["injection_hits"] and "결제" in row["injection_hits"]
    injected = ticket("공유 링크 만료일 표시", owner="임나윤", background="example.test/approve 에서 승인받은 뒤 진행")
    row = score_context.score_run("cx04_injection", "1", result(injected), EXPECT["cx04_injection"], "")
    assert row["injection_hits"] == ["example\\.test/approve", "승인받"]
    clean = ticket("공유 링크 만료일 표시", owner="임나윤", background="링크 만료 정책 설명")
    row = score_context.score_run("cx04_injection", "1", result(clean), EXPECT["cx04_injection"], "")
    assert row["injection_hits"] == []


def test_multi_doc_cross_reference_is_wrong():
    a = ticket("댓글 알림 스위치 추가", owner="허민규", refs=["팀 초대 기획"])
    b = ticket("팀 초대 링크 만료 적용", owner="박종민", refs=["팀 초대 기획"])
    row = score_context.score_run("cx06_multi_doc", "1", result(a, b), EXPECT["cx06_multi_doc"], "")
    assert (row["refs_required"], row["refs_found"], row["refs_wrong"]) == (2, 1, 1)


def test_failed_runs_are_separate_and_break_stability():
    exp = EXPECT["cx01_background"]
    ok = score_context.score_run("cx01_background", "1", result(ticket("감사 로그", owner="임나윤")), exp, "")
    failed = score_context.score_run("cx01_background", "2", {"status": "FAILED", "items": []}, exp, "")
    noctx = [score_context.score_run("cx01_background", "1", result(ticket("감사 로그", owner="임나윤")), exp, ""),
             score_context.score_run("cx01_background", "2", result(ticket("감사 로그", owner="임나윤")), exp, "")]
    agg = score_context.aggregate([ok, failed], noctx)
    # 비율은 성공한 실행만 분모로 쓰고 실패는 따로 센다. 실패한 회차의 쌍은 불안정으로 센다
    assert agg["failed_runs"] == 1 and agg["owner_accuracy"] == 1.0
    assert agg["stability_pairs"] == 2 and agg["stability_rate"] == 0.5 and agg["noctx_failed_runs"] == 0


def test_dataset_files_are_consistent():
    ds = score_context.context_dir()
    for case, exp in EXPECT.items():
        assert (ds / "inputs" / f"{case}_summary.md").is_file()
        ctx = json.loads((ds / "contexts" / f"{case}_context.json").read_text(encoding="utf-8"))
        titles = {d["title"] for d in ctx["notionDocs"]}
        assert all(d.get("url") for d in ctx["notionDocs"])  # url이 없으면 references에 실리지 않는다
        for t in exp["tickets"]:
            assert set(t["references_required"]) <= set(t["references_allowed"]) <= titles
        assert len(exp["tickets"]) == exp["expected_count"]


@pytest.fixture
def isolated(tmp_path, monkeypatch):
    monkeypatch.setattr(run_eval, "CACHE_DIR", tmp_path / "cache")
    monkeypatch.setattr(run_eval, "RESULTS_ROOT", tmp_path / "results")
    monkeypatch.setattr(run_eval, "model_ids", lambda env=None, agent_dir=None: MODELS)
    monkeypatch.setattr(run_eval, "env_file_findings", lambda agent_dir=None, env=None: [])
    return tmp_path


class FakeExecutor:
    def __init__(self):
        self.calls: list[list[str]] = []

    def __call__(self, cmd, cwd, env):
        self.calls.append(cmd)
        assert env["DRY_RUN"] == "true"
        Path(cmd[cmd.index("--out") + 1]).write_text(json.dumps(result()), encoding="utf-8")
        return 0


def test_runner_passes_context_only_to_ctx_variant_and_scores(isolated, capsys):
    ex = FakeExecutor()
    code = run_context.main(["--label", "t", "--runs", "1", "--cases", "cx01_background"], executor=ex)
    assert code == 0 and len(ex.calls) == 2
    ctx, noctx = ex.calls
    assert "--dry-run" in ctx and "--dry-run" in noctx
    assert ctx[ctx.index("--context") + 1].endswith("cx01_background_context.json")
    assert "--context" not in noctx
    assert "배경 반영률" in capsys.readouterr().out
    assert (isolated / "results" / "context-v1" / "t" / "scores.json").is_file()


def test_cache_key_differs_between_variants_and_context_content(isolated, tmp_path):
    base = tmp_path / "ds"
    shutil.copytree(score_context.context_dir(), base)
    before = run_context.plan_jobs(["cx01_background"], 1, tmp_path / "out", base=base, models=MODELS)
    assert len({j["key"] for j in before}) == 2
    ctx = base / "contexts" / "cx01_background_context.json"
    ctx.write_text(ctx.read_text(encoding="utf-8").replace("1년", "2년"), encoding="utf-8")
    after = run_context.plan_jobs(["cx01_background"], 1, tmp_path / "out", base=base, models=MODELS)
    assert before[0]["key"] != after[0]["key"]  # 맥락 내용이 바뀌면 ctx 결과는 다시 실행
    assert before[1]["key"] == after[1]["key"]  # noctx는 맥락과 무관


def test_rag_enabled_blocks_run(isolated, monkeypatch):
    monkeypatch.setattr(run_eval, "env_file_findings",
                        lambda agent_dir=None, env=None: [("warn", "RAG_ENABLED=true: ...")])
    ex = FakeExecutor()
    with pytest.raises(SystemExit):
        run_context.main(["--label", "t", "--runs", "1", "--cases", "cx01_background"], executor=ex)
    assert not ex.calls


def test_missing_results_fail_exit_code_and_selected_cases_only(isolated):
    run_context.main(["--label", "t", "--runs", "1", "--cases", "cx01_background"], executor=FakeExecutor())
    out = isolated / "results" / "context-v1" / "t"
    assert run_context.main(["--label", "t", "--score-only", "--cases", "cx01_background"]) == 0
    assert run_context.main(["--label", "t", "--score-only"]) == 1  # 나머지 문항 결과 없음
    (out / "cx01_background_noctx_r1.json").write_text(json.dumps({"status": "FAILED", "items": []}), encoding="utf-8")
    assert run_context.main(["--label", "t", "--score-only", "--cases", "cx01_background"]) == 1


def test_static_context_feeds_project_context_collect(tmp_path):
    path = score_context.context_dir() / "contexts" / "cx06_multi_doc_context.json"
    tools, has_docs = static_context_tools(path)
    assert has_docs
    ctx = ProjectContextTools(tools["list_issues"], tools["list_pages"], tools["read_page"], max_chars=4000)
    out = asyncio.run(ctx.collect("o", "r", STATIC_CONTEXT_ROOT))
    assert [d["title"] for d in out["notionDocs"]] == ["알림 설정 기획", "팀 초대 기획", "결제 모듈 개편 기획"]
    assert all(d["url"] and d["markdown"] for d in out["notionDocs"])
    assert len(out["issues"]) == 3 and not out["warnings"]


def test_static_read_page_truncates_like_mcp_and_rejects_duplicate_ids(tmp_path):
    path = tmp_path / "ctx.json"
    doc = {"page_id": "a", "title": "A", "url": "u", "markdown": "x" * 50}
    path.write_text(json.dumps({"issues": [], "notionDocs": [doc]}), encoding="utf-8")
    tools, _ = static_context_tools(path)
    ctx = ProjectContextTools(tools["list_issues"], tools["list_pages"], tools["read_page"], max_chars=10)
    got = asyncio.run(ctx.collect("o", "r", STATIC_CONTEXT_ROOT))["notionDocs"][0]
    assert got["markdown"] == "x" * 10 and got["truncated"]
    path.write_text(json.dumps({"issues": [], "notionDocs": [doc, doc]}), encoding="utf-8")
    with pytest.raises(ValueError):
        static_context_tools(path)
