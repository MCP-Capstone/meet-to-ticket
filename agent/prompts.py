"""LLM 프롬프트.

프롬프트 본문은 prompts/*.md에 두고 여기서는 읽기만 한다 (코드와 분리해 버전 관리).
PROMPT_VERSION은 프롬프트 파일 내용의 해시라서, 프롬프트를 고치면 값이 바뀐다.
실행 기록에 남겨 어떤 프롬프트로 만든 결과인지 비교할 때 쓴다.
"""

from __future__ import annotations

import hashlib
from pathlib import Path

PROMPT_DIR = Path(__file__).resolve().parent / "prompts"
PROMPT_FILES = ("extract_system", "draft_system", "draft_user", "review_system", "review_user",
                "dedupe_system", "dedupe_user")


def _load(name: str) -> str:
    # 파일 끝 줄바꿈 하나는 편집기가 붙이는 것이라 뺀다
    return (PROMPT_DIR / f"{name}.md").read_text(encoding="utf-8").removesuffix("\n")


def prompt_version(texts: dict[str, str]) -> str:
    digest = hashlib.sha256()
    for name in sorted(texts):
        digest.update(name.encode("utf-8") + b"\0" + texts[name].encode("utf-8") + b"\0")
    return digest.hexdigest()[:8]


TEXTS = {name: _load(name) for name in PROMPT_FILES}
PROMPT_VERSION = prompt_version(TEXTS)

EXTRACT_SYSTEM = TEXTS["extract_system"]  # 표가 없을 때 할 일·결정·미결 질문·제외 항목 추출
DRAFT_SYSTEM = TEXTS["draft_system"]    # 이슈 초안 작성
DRAFT_USER = TEXTS["draft_user"]        # format: summary, decisions, open_questions, labels, items
REVIEW_SYSTEM = TEXTS["review_system"]  # 검수
REVIEW_USER = TEXTS["review_user"]      # format: summary, source_note, excluded, drafts
DEDUPE_SYSTEM = TEXTS["dedupe_system"]  # 중복 판단 (도구 고르기)
DEDUPE_USER = TEXTS["dedupe_user"]      # format: title, action, evidence, keys, expected
