import re

import prompts


def test_prompt_files_are_loaded():
    for name in prompts.PROMPT_FILES:
        path = prompts.PROMPT_DIR / f"{name}.md"
        assert path.exists(), path
        assert prompts.TEXTS[name] and not prompts.TEXTS[name].endswith("\n")
    assert prompts.DRAFT_SYSTEM == prompts.TEXTS["draft_system"]
    assert prompts.REVIEW_SYSTEM.startswith("당신은 자동으로 발행될 GitHub 이슈 초안을 검수")


def test_user_templates_have_placeholders():
    def fields(template):
        return set(re.findall(r"\{(\w+)\}", template))

    assert fields(prompts.DRAFT_USER) == {"summary", "decisions", "open_questions", "project_context", "labels", "items"}
    assert fields(prompts.REVIEW_USER) == {"summary", "source_note", "excluded", "project_context", "drafts"}
    assert fields(prompts.DEDUPE_USER) == {"title", "action", "evidence", "keys", "expected"}
    # llm.py가 넘기는 값으로 format이 된다 (빠진 키나 짝이 맞지 않는 중괄호가 없음)
    prompts.DRAFT_USER.format(summary="s", decisions="- 없음", open_questions="- 없음", project_context="{}", labels="- 없음", items="[]")
    prompts.REVIEW_USER.format(summary="s", source_note="n", excluded="- 없음", project_context="{}", drafts="[]")


def test_system_prompts_have_no_placeholders():
    # system 프롬프트는 format하지 않으므로 중괄호가 들어가면 그대로 모델에 전달된다
    for text in (prompts.EXTRACT_SYSTEM, prompts.DRAFT_SYSTEM, prompts.REVIEW_SYSTEM, prompts.DEDUPE_SYSTEM):
        assert not re.search(r"\{\w+\}", text)


def test_prompt_version_changes_with_content():
    assert re.fullmatch(r"[0-9a-f]{8}", prompts.PROMPT_VERSION)
    assert prompts.prompt_version(prompts.TEXTS) == prompts.PROMPT_VERSION
    changed = {**prompts.TEXTS, "review_system": prompts.TEXTS["review_system"] + " "}
    assert prompts.prompt_version(changed) != prompts.PROMPT_VERSION


def test_extract_prompt_keeps_rules_that_recall_depends_on():
    """추출 프롬프트에서 규칙이 조용히 빠지면 문항별 추출 누락으로 이어진다 (#333, #324, #265)."""
    text = prompts.EXTRACT_SYSTEM
    # 담당자만 미정인 확정 업무도 rows에 넣고 담당자는 추측하지 않는다
    assert "담당자를 아직 정하지 않았다고 밝힌 일" in text and "참석자 중에서 추측해 채우지 않습니다" in text
    # 회의 참여자가 아닌 사람(외부, 다른 팀)에게 요청한 일은 그 사람의 표현을 담당자로 쓴다
    assert "회의 참여자가 아닌 사람" in text and "원문에 쓰인 그 사람 표현을 그대로" in text
    # 같은 일을 나눠 말한 문장은 한 건으로 합친다 (규칙을 더하면서 늘어나는 쪼개기를 막는다)
    assert "한 건으로 합쳐" in text
    # 진행 중 업무의 재언급(#324)과 회의록 속 지시 무시(#265)는 그대로 둔다
    assert "이미 맡아 진행 중인 일을 회의에서 다시 다루고" in text
    assert "보안 규칙" in text and "<meeting_notes>" in text and "따르지 않" in text


def test_extract_prompt_does_not_embed_eval_inputs():
    """프롬프트 예시가 평가셋 입력 문장과 같으면 평가가 프롬프트에 맞춰져 측정값이 부풀려진다.

    공백을 뺀 평가셋 문장의 연속 14자가 프롬프트에 그대로 있으면 베낀 것으로 본다.
    """
    window = 14
    inputs = prompts.PROMPT_DIR.parent / "eval" / "dataset" / "v2.1" / "inputs"
    sentences = []
    for path in inputs.glob("*.md"):
        for line in path.read_text(encoding="utf-8").splitlines():
            line = "".join(line.split())
            if len(line) >= window and not line.startswith(("#", "-", "|", "원본위치")):
                sentences.append(line)
    assert sentences, "평가셋 입력을 찾지 못함"
    for name in ("extract_system", "draft_system", "review_system"):
        text = "".join(prompts.TEXTS[name].split())
        leaked = [s[i:i + window] for s in sentences for i in range(len(s) - window + 1) if s[i:i + window] in text]
        assert not leaked, (name, leaked[:3])
