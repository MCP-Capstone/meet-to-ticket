from __future__ import annotations

from pathlib import Path

from schemas import ProjectMember

SAMPLES = Path(__file__).resolve().parent.parent / "samples"

MEMBERS = [
    ProjectMember(name="김민준", github_name="minjun-k"),
    ProjectMember(name="박서연", github_name="seoyeon-p"),
    ProjectMember(name="이도윤", github_name="doyun-l"),
    ProjectMember(name="최유진", github_name="yujin-c"),
    ProjectMember(name="정하늘", github_name=None),
    ProjectMember(name="오지훈", github_name="jihoon-o"),
]


def sample(name: str) -> str:
    return (SAMPLES / f"{name}_summary.md").read_text(encoding="utf-8")
