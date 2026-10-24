"""담당자·기한·예상 처리 해석 (코드). LLM은 이 값을 바꾸지 않는다."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date, timedelta

from schemas import ProjectMember

NO_DUE_WORDS = ("미정", "운영 전", "tbd", "추후", "협의")
KEY_PATTERN = re.compile(r"\b[A-Z][A-Z0-9]+-\d+\b")


# ---------------------------------------------------------------- 담당자


def split_names(raw: str) -> list[str]:
    parts = re.split(r"[,，、/·]|\s및\s", raw or "")
    names = []
    for part in parts:
        name = re.sub(r"\(.*?\)", "", part).strip()
        if name and name not in {"-", "미정", "없음"} and name not in names:
            names.append(name)
    return names


@dataclass
class Assignees:
    names: list[str]
    logins: list[str]
    unresolved: list[str]  # GitHub 로그인을 못 정한 이름 (멤버 불일치 + 계정 없음). 본문의 "계정 미매핑" 표기
    unmatched: list[str] = field(default_factory=list)  # 어떤 멤버와도 이름이 맞지 않은 이름. 담당자를 정할 수 없다
    ambiguous: list[str] = field(default_factory=list)  # 같은 이름의 멤버가 여럿이라 누구인지 정할 수 없는 이름


def map_assignees(names: list[str], members: list[ProjectMember]) -> Assignees:
    by_name: dict[str, list[ProjectMember]] = {}
    for m in members:
        by_name.setdefault(m.name.strip(), []).append(m)
    logins: list[str] = []
    unresolved: list[str] = []
    unmatched: list[str] = []
    ambiguous: list[str] = []
    for name in names:
        matched = by_name.get(name, [])
        member = matched[0] if len(matched) == 1 else None
        if not matched:
            unmatched.append(name)
        elif len(matched) > 1:
            # 동명이인은 백엔드 AssigneeResolver처럼 담당자를 정하지 않는다
            ambiguous.append(name)
        login = (member.github_name or "").strip() if member else ""
        if login:
            if login not in logins:
                logins.append(login)
        else:
            unresolved.append(name)
    return Assignees(names=names, logins=logins, unresolved=unresolved, unmatched=unmatched,
                     ambiguous=ambiguous)


# ---------------------------------------------------------------- 기한


@dataclass
class Due:
    text: str
    date: str | None = None   # YYYY-MM-DD
    time: str | None = None   # HH:MM

    def display(self) -> str:
        if not self.date:
            return f"없음 (원문: {self.text})" if self.text else "없음"
        value = self.date + (f" {self.time}" if self.time else "")
        return value if value == self.text else f"{value} (원문: {self.text})"


# 연도가 있는 숫자 날짜: 2026-10-31, 2026.10.31, 2026. 10. 31., 2026/10/31 (구분자는 한 종류로 통일)
_FULL_DATE = re.compile(r"(?<!\d)(\d{4})\s*([-./])\s*(\d{1,2})\s*\2\s*(\d{1,2})(?!\d)")
_KO_DATE = re.compile(r"(\d{1,2})\s*월\s*(\d{1,2})\s*일")
# 연도 없는 숫자 날짜: 10. 31., 10. 31, 10. 31일, 10.31, 10/31. 시각(10:30), 버전(1.2.3, v1.2),
# 소수(2.5배, 1.5주)는 제외한다. "1.5일"처럼 붙여 쓴 소수 + 일은 기간으로 보고, "10. 31일"처럼 띄우면 날짜로 본다
_MONTH_DAY = re.compile(
    r"(?<![A-Za-z\d.:/-])(\d{1,2})(\s*[./]\s*)(\d{1,2})(?!\d|[.:/]\d|%|배|시간|주|개월|년)"
)
# 월 없는 날짜: 20일, 26일 17시. "3일 안에", "2일간", "1일차", "3일 후" 같은 기간과 "1.5일"은 제외한다
_DAY_ONLY = re.compile(r"(?<![\d.월])(\d{1,2})\s*일(?!\s*(?:간|동안|째|차|치|안에|안으로|이내|내로|내에|후|뒤))")
# 주 단위 요일: 이번 주 금요일, 다음주 수요일. 요일만: 금요일까지
_WEEK_DAY = re.compile(r"(이번|다음|다다음)\s*주\s*([월화수목금토일])요일")
_WEEKDAY = re.compile(r"(?<![가-힣])([월화수목금토일])요일")
_WEEKDAYS = "월화수목금토일"
_WEEK_OFFSET = {"이번": 0, "다음": 1, "다다음": 2}


def _month_day(text: str) -> tuple[int, int] | None:
    for m in _MONTH_DAY.finditer(text):
        if text[m.end():m.end() + 1] == "일" and m.group(2) == ".":
            continue  # 1.5일: 기간
        return int(m.group(1)), int(m.group(3))
    return None


def _make_date(year: int, month: int, day: int) -> date | None:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def _day_only(text: str, meeting_date: date) -> date | None:
    """월 없는 "N일"은 회의일(당일 포함) 이후 가장 가까운 N일이다. 그 달에 N일이 지났거나 없으면 다음 달로 넘긴다."""
    m = _DAY_ONLY.search(text)
    if not m or "지난" in text[max(0, m.start() - 4):m.start()]:
        return None
    day = int(m.group(1))
    year, month = meeting_date.year, meeting_date.month
    for _ in range(3):  # 31일처럼 다음 달에도 없으면 한 달 더 본다
        found = _make_date(year, month, day)
        if found and found >= meeting_date:
            return found
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return None


def _weekday(text: str, meeting_date: date) -> date | None:
    """"다음 주 수요일"은 회의일이 속한 주(월요일 시작) 기준, 요일만 있으면 회의일 다음 날부터 가장 가까운 그 요일이다."""
    if m := _WEEK_DAY.search(text):
        monday = meeting_date - timedelta(days=meeting_date.weekday())
        return monday + timedelta(weeks=_WEEK_OFFSET[m.group(1)], days=_WEEKDAYS.index(m.group(2)))
    if m := _WEEKDAY.search(text):
        ahead = (_WEEKDAYS.index(m.group(1)) - meeting_date.weekday()) % 7 or 7
        return meeting_date + timedelta(days=ahead)
    return None


def _infer_year(month: int, day: int, meeting_date: date | None) -> date | None:
    """연도 없는 날짜는 회의 연도로 보고, 회의일보다 180일 넘게 과거면 다음 해로 넘긴다."""
    base_year = meeting_date.year if meeting_date else date.today().year
    found = _make_date(base_year, month, day)
    if found and meeting_date and (meeting_date - found).days > 180:
        found = _make_date(base_year + 1, month, day)
    return found


def parse_due(raw: str, meeting_date: date | None) -> Due:
    text = (raw or "").strip()
    due = Due(text=text)
    if not text or any(w in text.lower() for w in NO_DUE_WORDS):
        return due

    found: date | None = None
    if m := _FULL_DATE.search(text):
        found = _make_date(int(m.group(1)), int(m.group(3)), int(m.group(4)))
    elif m := _KO_DATE.search(text):
        found = _infer_year(int(m.group(1)), int(m.group(2)), meeting_date)
    elif md := _month_day(text):
        found = _infer_year(md[0], md[1], meeting_date)
    elif meeting_date and "오늘" in text:
        found = meeting_date
    elif meeting_date and "모레" in text:  # "내일모레"도 모레다
        found = meeting_date + timedelta(days=2)
    elif meeting_date and "내일" in text:
        found = meeting_date + timedelta(days=1)
    elif meeting_date:
        found = _day_only(text, meeting_date) or _weekday(text, meeting_date)
    if found is None:
        return due
    due.date = found.isoformat()

    m = re.search(r"(\d{1,2}):(\d{2})", text)
    k = re.search(r"(오전|오후)?\s*(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분)?", text)
    if m:
        due.time = f"{int(m.group(1)):02d}:{m.group(2)}"
    elif k:
        hour = int(k.group(2))
        if k.group(1) == "오후" and hour < 12:
            hour += 12
        due.time = f"{hour:02d}:{int(k.group(3) or 0):02d}"
    # "오전", "오후", "퇴근 전"만 있으면 시각을 정하지 않는다. 임의의 시각을 만들지 않고 원문 표현(text)으로만 남긴다
    return due


# ---------------------------------------------------------------- 예상 티켓 처리


@dataclass
class Expected:
    raw: str
    kind: str  # new | update | conditional_update | sub_ticket | hold | unknown
    keys: list[str] = field(default_factory=list)


def classify_expected(raw: str) -> Expected:
    text = (raw or "").strip()
    keys = KEY_PATTERN.findall(text)
    if "보류" in text:
        kind = "hold"
    elif "조건부" in text:
        kind = "conditional_update"
    elif "하위" in text:
        kind = "sub_ticket"
    elif "갱신" in text or "기존" in text:
        kind = "update"
    elif "신규" in text:
        kind = "new"
    else:
        kind = "unknown"
    return Expected(raw=text, kind=kind, keys=keys)


def normalize_ws(text: str) -> str:
    return re.sub(r"\s+", " ", text or "").strip()
