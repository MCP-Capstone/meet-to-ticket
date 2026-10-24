from datetime import date

import pytest

from conftest import MEMBERS, sample
from parsing import parse_summary
from schemas import ProjectMember
from resolve import classify_expected, map_assignees, parse_due, split_names


@pytest.mark.parametrize("name,count", [("10min_1", 8), ("30min_1", 13), ("60min_1", 24)])
def test_action_rows_count(name, count):
    parsed = parse_summary(sample(name))
    assert len(parsed.rows) == count
    assert parsed.meeting_id == name


def test_parse_10min_details():
    p = parse_summary(sample("10min_1"))
    assert p.meeting_date == date(2026, 10, 19)
    first = p.rows[0]
    assert first.work_key == "10m1-a1"
    assert first.assignees_raw == "박서연"
    assert first.due_raw == "2026-10-20"
    assert first.expected_raw == "신규 티켓"
    assert "세 고객사" in p.background
    assert len(p.open_questions) == 2
    assert p.excluded == ["알림 센터 연동", "일반 관리자용 역할 설정 화면의 세부 export 권한"]


def test_60min_excluded_none():
    assert parse_summary(sample("60min_1")).excluded == []


def test_split_and_map_names():
    names = split_names("김민준, 박서연, 이도윤, 최유진, 정하늘, 오지훈")
    assert len(names) == 6
    a = map_assignees(names, MEMBERS)
    assert a.logins == ["minjun-k", "seoyeon-p", "doyun-l", "yujin-c", "jihoon-o"]
    assert a.unresolved == ["정하늘"]
    assert a.unmatched == [] and a.ambiguous == []  # 정하늘은 멤버이고 계정만 없다
    missing = map_assignees(["없는사람"], MEMBERS)
    assert missing.unresolved == ["없는사람"] and missing.unmatched == ["없는사람"]


def test_map_names_with_duplicate_members_is_ambiguous():
    # 동명이인은 누구인지 정할 수 없어 로그인을 붙이지 않는다
    members = [*MEMBERS, ProjectMember(name="박서연", github_name=None)]
    a = map_assignees(["박서연", "김민준"], members)
    assert a.logins == ["minjun-k"]
    assert a.ambiguous == ["박서연"] and a.unmatched == []
    assert a.unresolved == ["박서연"]


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("2026-10-20", ("2026-10-20", None)),
        ("2026-10-20 오전", ("2026-10-20", "12:00")),
        ("2026-10-19 오후", ("2026-10-19", "18:00")),
        ("2026-10-19 퇴근 전", ("2026-10-19", "18:00")),
        ("2026-10-22 14:00", ("2026-10-22", "14:00")),
        ("2026-11-18 15:00", ("2026-11-18", "15:00")),
        ("10월 28일", ("2026-10-28", None)),
        ("미정", (None, None)),
        ("운영 전", (None, None)),
        ("", (None, None)),
    ],
)
def test_parse_due(raw, expected):
    due = parse_due(raw, date(2026, 10, 26))
    assert (due.date, due.time) == expected
    assert due.text == raw


def test_parse_due_year_rollover():
    assert parse_due("1월 5일", date(2026, 12, 20)).date == "2027-01-05"


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("10. 31.", ("2026-10-31", None)),
        ("10. 31", ("2026-10-31", None)),
        ("10. 31일", ("2026-10-31", None)),
        ("10.31", ("2026-10-31", None)),
        ("10/31", ("2026-10-31", None)),
        ("2026. 10. 31.", ("2026-10-31", None)),
        ("2026.10.31", ("2026-10-31", None)),
        ("2026/10/31", ("2026-10-31", None)),
        ("10/31 오후 3시", ("2026-10-31", "15:00")),
        ("10. 31. 15:00", ("2026-10-31", "15:00")),
        ("10.31 오전", ("2026-10-31", "12:00")),
        # 날짜가 아니거나 잘못된 날짜는 예외 없이 기한 없음
        ("13/40", (None, None)),
        ("2/30", (None, None)),
        ("2026-13-40", (None, None)),
        ("2026/02/30", (None, None)),
        ("10:30", (None, None)),
        ("v1.2", (None, None)),
        ("1.2.3", (None, None)),
        ("1.5일", (None, None)),
        ("2.5배", (None, None)),
    ],
)
def test_parse_due_numeric_dates(raw, expected):
    due = parse_due(raw, date(2026, 10, 26))
    assert (due.date, due.time) == expected


def test_parse_due_numeric_year_rollover():
    assert parse_due("1/5", date(2026, 12, 20)).date == "2027-01-05"
    assert parse_due("1. 5.", date(2026, 12, 20)).date == "2027-01-05"
    assert parse_due("12/31", date(2026, 12, 20)).date == "2026-12-31"


@pytest.mark.parametrize(
    "raw,kind,keys",
    [
        ("신규 티켓", "new", []),
        ("기존 이슈 갱신 (AUTH-112)", "update", ["AUTH-112"]),
        ("조건부 기존 이슈 갱신 (IAM-48)", "conditional_update", ["IAM-48"]),
        ("기존 이슈의 하위 티켓 (AUTH-112)", "sub_ticket", ["AUTH-112"]),
        ("신규 티켓 (AUTH-87)", "new", ["AUTH-87"]),
        ("발행 보류", "hold", []),
    ],
)
def test_classify_expected(raw, kind, keys):
    e = classify_expected(raw)
    assert e.kind == kind and e.keys == keys


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("내일 오후 5시", ("2026-10-23", "17:00")),
        ("내일 오후 6시", ("2026-10-23", "18:00")),
        ("내일 오전 10시 30분", ("2026-10-23", "10:30")),
        ("오늘 오후 12시", ("2026-10-22", "12:00")),
    ],
)
def test_parse_due_korean_hour(raw, expected):
    due = parse_due(raw, date(2026, 10, 22))
    assert (due.date, due.time) == expected
