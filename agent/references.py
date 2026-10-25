"""참고 문서 표시 검증 (#335).

초안이 reference_titles에 적은 기획 문서가 배경/설명에 실제로 쓰였는지 코드로 확인한다.
프롬프트 규칙("반영한 문서만 적는다")만으로는 문서 내용을 쓰지 않고 제목만 적는 일이 있었다.

판단 기준: 문서 본문(제목 줄 제외)과 배경/설명이 4글자 이상 겹치는 구절을 갖되, 그 구절이 회의록 원문에는 없어야 한다.
- 회의록에도 있는 구절은 문서를 읽지 않고도 쓸 수 있으므로 문서를 반영했다는 증거로 보지 않는다.
- 구절은 문장부호/줄바꿈 단위로 자르고 공백을 없앤 뒤 비교한다 ("개인정보 보호"와 "개인정보보호"를 같게 본다).
- 한 구절이라도 겹치면 반영한 것으로 본다. 기획 문서의 사실이 하나도 없는 경우만 걸러내는 느슨한 검사다.
- 본문이 없는 문서(조회 실패 등)는 판단할 수 없으므로 반영한 것으로 둔다.
"""

from __future__ import annotations

import re

GRAM = 4
_SEPARATORS = re.compile(r"[^0-9A-Za-z가-힣 ]+")
_SPACES = re.compile(r"\s+")


def _segments(text: str) -> list[str]:
    segments = (_SPACES.sub("", part) for part in _SEPARATORS.split((text or "").lower()))
    return [s for s in segments if len(s) >= GRAM]


def _grams(text: str) -> set[str]:
    return {s[i:i + GRAM] for s in _segments(text) for i in range(len(s) - GRAM + 1)}


def reflects_document(doc_markdown: str, doc_title: str, drafted_text: str, source: str) -> bool:
    """문서 본문의 구절이 초안 본문(배경/설명)에 있고 회의록 원문에는 없으면 True."""
    doc = _grams(doc_markdown) - _grams(doc_title)
    if not doc:
        return True
    shared = doc & _grams(drafted_text)
    if not shared:
        return False
    in_source = "|".join(_segments(source))
    return any(gram not in in_source for gram in shared)
