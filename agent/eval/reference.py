"""평가셋 정답(reference) 파일과 manifest 읽기.

정답 파일은 dataset/<버전>/references/ 에만 두고, 에이전트에는 inputs/ 만 넘긴다.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
DEFAULT_VERSION = "v2.1"

KEY_RE = re.compile(r"[A-Z]{2,}\d*-\d+[A-Z]?")
COUNT_RE = re.compile(r"기대 신규 업무 수:\s*\*\*(\d+)건\*\*")
DUE_RE = re.compile(r"(\d{4}-\d{2}-\d{2})(?:\s+(\d{2}:\d{2}))?")
NEEDS_CHECK = "확인 필요"
EXTERNAL_PREFIX = "외부:"


def dataset_dir(version: str = DEFAULT_VERSION, base: Path | None = None) -> Path:
    return (base or EVAL_DIR / "dataset") / version


@dataclass(frozen=True)
class RefTask:
    key: str
    title: str
    owner: str       # 담당자 이름, "확인 필요", "외부: 이름" 중 하나
    due_raw: str
    date: str | None  # 기한 날짜. 없으면 None
    time: str | None  # 기한 시각. 오전/오후/퇴근 전처럼 시각이 없으면 None이 정답


@dataclass(frozen=True)
class Reference:
    question: str
    expected_count: int
    tasks: tuple[RefTask, ...]


def parse_due(raw: str) -> tuple[str | None, str | None]:
    m = DUE_RE.match(raw.strip())
    if not m:
        return None, None
    return m.group(1), m.group(2)


def parse_reference(question: str, text: str) -> Reference:
    count_match = COUNT_RE.search(text)
    if not count_match:
        raise ValueError(f"{question}: '기대 신규 업무 수'를 찾지 못했습니다")
    tasks = []
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) >= 4 and KEY_RE.fullmatch(cells[0]):
            date, time = parse_due(cells[3])
            tasks.append(RefTask(cells[0], cells[1], cells[2], cells[3], date, time))
    return Reference(question, int(count_match.group(1)), tuple(tasks))


def load_reference(question: str, version: str = DEFAULT_VERSION, base: Path | None = None) -> Reference:
    path = dataset_dir(version, base) / "references" / f"{question}_reference.md"
    return parse_reference(question, path.read_text(encoding="utf-8"))


def load_manifest(version: str = DEFAULT_VERSION, base: Path | None = None) -> dict:
    return json.loads((dataset_dir(version, base) / "manifest.json").read_text(encoding="utf-8"))


def load_member_names(version: str = DEFAULT_VERSION, base: Path | None = None) -> set[str]:
    members = json.loads((dataset_dir(version, base) / "members.json").read_text(encoding="utf-8"))
    return {m["name"] for m in members}


def question_ids(version: str = DEFAULT_VERSION, base: Path | None = None) -> list[str]:
    """회의 요약본 파일 이름 기준의 문항 ID 목록 (정렬)."""
    inputs = dataset_dir(version, base) / "inputs"
    return sorted(p.name.removesuffix("_summary.md") for p in inputs.glob("*_summary.md"))
