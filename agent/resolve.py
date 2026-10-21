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
    unresolved: list[str]


def map_assignees(names: list[str], members: list[ProjectMember]) -> Assignees:
    by_name = {m.name.strip(): m for m in members}
    logins: list[str] = []
    unresolved: list[str] = []
    for name in names:
        member = by_name.get(name)
        login = (member.github_name or "").strip() if member else ""
        if login:
            if login not in logins:
                logins.append(login)
        else:
            unresolved.append(name)
    return Assignees(names=names, logins=logins, unresolved=unresolved)


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


def parse_due(raw: str, meeting_date: date | None) -> Due:
    text = (raw or "").strip()
    due = Due(text=text)
    if not text or any(w in text.lower() for w in NO_DUE_WORDS):
        return due

    found: date | None = None
    m = re.search(r"(\d{4})-(\d{1,2})-(\d{1,2})", text)
    if m:
        found = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    else:
        m = re.search(r"(\d{1,2})\s*월\s*(\d{1,2})\s*일", text)
        if m:
            base_year = meeting_date.year if meeting_date else date.today().year
            month, day = int(m.group(1)), int(m.group(2))
            found = date(base_year, month, day)
            if meeting_date and (meeting_date - found).days > 180:
                found = date(base_year + 1, month, day)
        elif meeting_date and "오늘" in text:
            found = meeting_date
        elif meeting_date and "내일" in text:
            found = meeting_date + timedelta(days=1)
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
    elif "오전" in text:
        due.time = "12:00"
    elif "오후" in text or "퇴근" in text:
        due.time = "18:00"
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
