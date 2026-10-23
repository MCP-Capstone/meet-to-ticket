"""평가셋 채점기 테스트. LLM 호출 없이 저장된 결과 모양의 모의 데이터로 돈다."""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

from eval import score, score_dedupe, score_extract
from eval.reference import (EVAL_DIR, load_manifest, load_member_names, load_reference, parse_reference,
                            question_ids)

MEMBERS = load_member_names()
DEDUPE = load_manifest()["dedupe"]
EXTRACT_IDS = [q for q in question_ids() if q not in DEDUPE]


def item(title, names, date=None, time=None, text="", github=None, action=""):
    return {"title": title, "contentJson": {
        "action_text": action or title, "evidence_quotes": [],
        "assignees": {"names": names, "github": github or [], "unresolved": []},
        "due": {"text": text, "date": date, "time": time}}}


def result(items, status="SUCCEEDED", ms=1000):
    return {"status": status, "items": items, "metrics": {"durationMs": ms}}


def perfect_items(ref):
    out = []
    for t in ref.tasks:
        owner = t.owner
        names = [] if owner == "확인 필요" else [owner.removeprefix("외부:").strip()] if owner.startswith("외부:") else [owner]
        out.append(item(t.title, names, t.date, t.time, t.due_raw))
    return out


# ---------------------------------------------------------------- 평가셋 구성


def test_dataset_is_consistent():
    ids = question_ids()
    assert len(ids) == 30
    assert set(DEDUPE) <= set(ids)
    ds = EVAL_DIR / "dataset" / "v2.1"
    for q in ids:
        assert (ds / "references" / f"{q}_reference.md").exists(), q
    for q in EXTRACT_IDS:
        ref = load_reference(q)
        assert ref.expected_count == len(ref.tasks), q
    assert sum(load_reference(q).expected_count for q in EXTRACT_IDS if q.startswith(("10min", "30min", "60min"))) == 10
    assert len(MEMBERS) == 6


def test_agent_inputs_do_not_leak_reference():
    ds = EVAL_DIR / "dataset" / "v2.1"
    for p in (ds / "inputs").glob("*.md"):
        text = p.read_text(encoding="utf-8")
        assert "기대 신규 업무 수" not in text and "하면 안 되는 것" not in text, p.name


def test_eval_files_have_no_banned_characters_or_local_paths():
    banned = (chr(0x2014), chr(0xB7), "/Users/", "Desk" + "top", "ghp_", "github_pat_")
    for p in EVAL_DIR.rglob("*"):
        if p.is_file() and p.suffix in {".md", ".json", ".csv", ".py"} and not {"results", ".cache"} & set(p.parts):
            text = p.read_text(encoding="utf-8")
            for b in banned:
                assert b not in text, f"{p.name}: {b!r}"
            assert not re.search(r"sk-[A-Za-z0-9]{20}", text), p.name


def test_parse_reference_variants():
    text = """기대 신규 업무 수: **2건**.

| 업무 키 | 신규 업무 | 담당자 | 기한 | x |
|---|---|---|---|---|
| EV-12 | 버튼 수정 | 임나윤 | 2026-10-23 오전 | a |
| EV-24A | 문서 작성 | 외부: 보안팀 김과장 | 없음 (기존 이슈 기한 유지) | b |
"""
    ref = parse_reference("q", text)
    assert ref.expected_count == 2
    a, b = ref.tasks
    assert (a.date, a.time) == ("2026-10-23", None)
    assert (b.date, b.time) == (None, None)
    with pytest.raises(ValueError):
        parse_reference("q", "표만 있음")


# ---------------------------------------------------------------- 추출 채점


def run(q, items, **kw):
    return score_extract.score_run(q, "1", result(items, **kw), load_reference(q), MEMBERS, None)


def test_perfect_result_for_every_extract_question_is_satisfied():
    for q in EXTRACT_IDS:
        row = run(q, perfect_items(load_reference(q)))
        assert row["satisfied"], (q, row["detail"])


def test_key_match_and_split_tickets_count_as_extra():
    ref = load_reference("30min_1")
    base = item("CSV-08 0건 오류 수정", ["박종민"], "2026-10-23", "17:00")
    split = item("0건 BOM 파일 생성", ["박종민"], "2026-10-23", "17:00", action="CSV-08 완료 조건")
    row = run("30min_1", [base, split, item("재현 로그 증빙", ["임나윤"])])
    assert (row["matched"], row["extra"], row["extracted"]) == (1, 2, 3)
    assert not row["satisfied"] and not row["count_match"]
    assert ref.expected_count == 1


def test_due_date_without_month_and_wrong_time():
    row = run("30min_1", [item("CSV-08 수정", ["박종민"], None, None, "23일 17시")])
    assert (row["owner_ok"], row["due_date_ok"], row["due_ok"]) == (1, 0, 0)
    row = run("30min_1", [item("CSV-08 수정", ["박종민"], "2026-10-23", "12:00")])
    assert (row["due_date_ok"], row["due_ok"]) == (1, 0)


def test_morning_due_requires_empty_time():
    ok = run("ev12_morning_due", [item("CSV 재시도 버튼 포커스 복원", ["임나윤"], "2026-10-23", None, "금요일 오전")])
    bad = run("ev12_morning_due", [item("CSV 재시도 버튼 포커스 복원", ["임나윤"], "2026-10-23", "12:00")])
    assert ok["satisfied"] and not bad["satisfied"] and bad["due_date_ok"] == 1


def test_needs_check_and_external_owner():
    title = "관리자 CSV 권한 체크리스트 갱신"
    assert run("ev16_ambiguous_assignee", [item(title, ["민규님"], "2026-10-24")])["satisfied"]
    assert not run("ev16_ambiguous_assignee", [item(title, ["강민규"], "2026-10-24")])["satisfied"]
    ext = "CSV 감사 로그 항목 보안 검토"
    assert run("ev18_external_assignee", [item(ext, ["보안팀 김과장"], "2026-10-27")])["satisfied"]
    assert not run("ev18_external_assignee", [item(ext, ["박종민"], "2026-10-27")])["satisfied"]
    assert not run("ev18_external_assignee", [item(ext, ["보안팀 김과장"], "2026-10-27", github=["x"])])["satisfied"]


def test_zero_expected_meeting_penalizes_any_ticket():
    assert run("ev21_zero_action", [])["satisfied"]
    row = run("ev21_zero_action", [item("완료된 작업", ["박종민"])])
    assert row["extra"] == 1 and not row["satisfied"]


def test_similarity_link_needs_enough_overlap():
    row = run("ev11_relative_due", [item("전혀 다른 문서 번역 작업", ["최윤경"])])
    assert row["matched"] == 0 and row["extra"] == 1
    row = run("ev11_relative_due", [item("CSV 상태 API 시간 초과 처리 수정", ["박종민"], "2026-10-28")])
    assert row["matched"] == 1 and row["satisfied"]


def test_forbidden_text_and_failed_status():
    ref = load_reference("ev30_pii_redaction")
    items = perfect_items(ref)
    items[0]["contentJson"]["description"] = "연락처 010-0000-0000"
    row = score_extract.score_run("ev30_pii_redaction", "1", result(items), ref, MEMBERS, ["010-0000-0000"])
    assert row["forbidden_hit"] == ["010-0000-0000"] and not row["satisfied"]
    assert not run("ev11_relative_due", perfect_items(load_reference("ev11_relative_due")), status="FAILED")["satisfied"]


def test_aggregate_matches_baseline_style_pooling():
    rows = [run("30min_1", [item("CSV-08 수정", ["박종민"], "2026-10-23", "17:00"), item("추가 티켓", ["임나윤"])]),
            run("ev21_zero_action", [])]
    agg = score_extract.aggregate(rows)
    assert (agg["extracted"], agg["matched"], agg["expected_tasks"]) == (2, 1, 1)
    assert agg["precision"] == 0.5 and agg["recall"] == 1.0
    assert agg["due_accuracy"] == 1.0 and agg["owner_error_rate"] == 0.0
    assert agg["count_match_rate"] == 0.5 and agg["satisfied_rate"] == 0.5


# ---------------------------------------------------------------- 중복 판단 채점


def dd_item(relation, number, **extra):
    return {"title": "t", "contentJson": {"dedupe": {"relation": relation, "issue_number": number, **extra}}}


def test_handling_reads_new_and_legacy_formats():
    assert score_dedupe.actual_handling(dd_item("SAME", 2, handling={"type": "LINK", "issue_number": 2})) == "LINK"
    assert score_dedupe.actual_handling(dd_item("CHANGE", 1, handling={"type": "UPDATE", "issue_number": 1})) == "UPDATE"
    assert score_dedupe.actual_handling(dd_item("RELATED", 4, handling={"type": "CREATE", "issue_number": 4})) == "CREATE+REF"
    assert score_dedupe.actual_handling(dd_item("UNRELATED", None, handling={"type": "CREATE", "issue_number": None})) == "CREATE"
    assert score_dedupe.actual_handling(dd_item("SAME", 2, linked_existing=True)) == "LINK"
    assert score_dedupe.actual_handling(dd_item("CHANGE", 1)) == "CREATE+REF"


def test_dedupe_scoring_and_aggregate():
    e = DEDUPE["ev42_change_open"]
    good = score_dedupe.score_run("ev42_change_open", "1", result([dd_item("CHANGE", 1, handling={"type": "UPDATE", "issue_number": 1})]), e)
    legacy = score_dedupe.score_run("ev42_change_open", "2", result([dd_item("CHANGE", 1)]), e)
    wrong = score_dedupe.score_run("ev45_related", "1", result([dd_item("UNRELATED", None), dd_item("UNRELATED", None)]),
                                   DEDUPE["ev45_related"])
    assert good["judgment_ok"] and good["handling_ok"]
    assert legacy["judgment_ok"] and not legacy["handling_ok"]
    assert not wrong["relation_ok"] and not wrong["target_ok"]
    agg = score_dedupe.aggregate([good, legacy, wrong])
    assert agg["judgment_accuracy"] == pytest.approx(2 / 3)
    assert agg["handling_rate"] == pytest.approx(1 / 3)
    assert agg["extra_tickets"] == 1


# ---------------------------------------------------------------- 폴더 채점과 지표표


def write_results(tmp_path: Path, factor=1.0, runs=1) -> Path:
    d = tmp_path / "res"
    d.mkdir()
    for q in question_ids():
        for n in range(1, runs + 1):
            if q in DEDUPE:
                e = DEDUPE[q]
                handling = {"CREATE+REF": "CREATE", "CREATE": "CREATE"}.get(e["handling"], e["handling"])
                items = [dd_item(e["relation"], e["issue"], handling={"type": handling, "issue_number": e["issue"]})]
            else:
                items = perfect_items(load_reference(q))
            (d / f"{q}_r{n}.json").write_text(json.dumps(result(items, ms=int(10000 * factor))), encoding="utf-8")
    return d


def test_score_dir_perfect_meets_all_targets(tmp_path):
    summary = score.score_dir(write_results(tmp_path, runs=2))
    assert summary["missing"] == []
    assert summary["extract"]["runs"] == 48 and summary["dedupe"]["runs"] == 12
    assert summary["extract"]["precision"] == 1.0 and summary["dedupe"]["judgment_accuracy"] == 1.0
    rows = {r["id"]: r for r in score.check_targets(summary)}
    assert rows["precision"]["ok"] and rows["judgment"]["ok"] and rows["handling"]["ok"]
    assert rows["time_extract"]["ok"] is None  # baseline 없이는 처리 시간 판정 불가


def test_time_guard_and_compare(tmp_path):
    before = score.score_dir(write_results(tmp_path, factor=1.0))
    slow_dir = tmp_path / "slow"
    slow_dir.mkdir(parents=True)
    after = score.score_dir(write_results(slow_dir, factor=2.0))
    rows = {r["id"]: r for r in score.check_targets(after, before)}
    assert rows["time_extract"]["ok"] is False and rows["time_dedupe"]["ok"] is False
    ok_rows = {r["id"]: r for r in score.check_targets(before, before)}
    assert ok_rows["time_extract"]["ok"] is True
    text = score.format_report(after, before)
    assert "미달" in text and "운영 측정" in text


def test_missing_questions_are_reported(tmp_path):
    d = write_results(tmp_path)
    (d / "ev11_relative_due_r1.json").unlink()
    summary = score.score_dir(d)
    assert summary["missing"] == ["ev11_relative_due"]
    assert "ev11_relative_due" in score.format_report(summary)


def test_cli_writes_scores_and_rejects_version_mismatch(tmp_path, capsys):
    d = write_results(tmp_path)
    assert score.main([str(d)]) == 0
    scores = json.loads((d / "scores.json").read_text(encoding="utf-8"))
    assert scores["version"] == "v2.1"
    scores["version"] = "v1"
    other = tmp_path / "old.json"
    other.write_text(json.dumps(scores), encoding="utf-8")
    with pytest.raises(SystemExit):
        score.main([str(d), "--baseline", str(other)])
    assert "추출 정밀도" in capsys.readouterr().out


# ---------------------------------------------------------------- 알림 항목, 오매칭, 개인정보 판정


def notice_item():
    return {"title": "회의 결과 공지", "workKey": "MTG-11-notice",
            "contentJson": {"publish_target": "SLACK", "description": "공지", "dedupe": None,
                            "assignees": {"names": [], "github": [], "unresolved": []}}}


def test_slack_notice_is_not_counted_as_ticket():
    good = perfect_items(load_reference("ev11_relative_due"))
    row = run("ev11_relative_due", [*good, notice_item()])
    assert row["extracted"] == 1 and row["extra"] == 0 and row["count_match"] and row["satisfied"]
    zero = run("ev21_zero_action", [notice_item()])
    assert zero["extracted"] == 0 and zero["satisfied"]
    notion = item("조사", ["박종민"])
    notion["contentJson"]["publish_target"] = "NOTION"
    assert len(score_extract.ticket_items(result([notion, notice_item()]))) == 1


def test_dedupe_ignores_slack_notice():
    e = DEDUPE["ev41_same_open"]
    row = score_dedupe.score_run("ev41_same_open", "1", result([notice_item(), dd_item("SAME", 2)]), e)
    assert row["items"] == 1 and row["judgment_ok"]
    assert score_dedupe.aggregate([row])["extra_tickets"] == 0


def test_owner_bonus_alone_does_not_link_unrelated_ticket():
    # 담당자만 같고 제목이 전혀 다른 티켓은 정답과 연결하지 않는다
    other = item("로그인 화면 비밀번호 문구 정리", ["박종민"], "2026-10-28")
    row = run("ev11_relative_due", [other])
    assert row["matched"] == 0 and row["extra"] == 1 and not row["satisfied"]
    assert score_extract._similarity(load_reference("ev11_relative_due").tasks[0], other) == 0.0


def test_pii_checked_in_body_fields_only_and_evidence_is_warning():
    ref = load_reference("ev30_pii_redaction")
    clean = perfect_items(ref)
    clean[0]["contentJson"]["evidence_quotes"] = ["연락처 010-0000-0000"]
    row = score_extract.score_run("ev30_pii_redaction", "1", result(clean), ref, MEMBERS, ["010-0000-0000"])
    assert row["forbidden_hit"] == [] and row["forbidden_in_evidence"] == ["010-0000-0000"] and row["satisfied"]
    agg = score_extract.aggregate([row])
    assert agg["evidence_leak_runs"] == 1
    assert "경고" in score.format_report({"version": "v2.1", "missing": [], "extract": agg, "dedupe": None})
    leaked = perfect_items(ref)
    leaked[0]["contentJson"]["acceptance_criteria"] = ["qa.customer@example.test 로 회신"]
    row = score_extract.score_run("ev30_pii_redaction", "1", result(leaked), ref, MEMBERS, ["qa.customer@example.test"])
    assert row["forbidden_hit"] == ["qa.customer@example.test"] and not row["satisfied"]


def test_manifest_counts_match_dataset():
    manifest = load_manifest()
    assert manifest["meeting_input_count"] == len(question_ids())
    rows = (EVAL_DIR / "dataset" / "v2.1" / "questions.csv").read_text(encoding="utf-8").splitlines()[1:]
    assert manifest["scored_unit_count"] == sum(int(r.split(",")[-1]) for r in rows if r)
