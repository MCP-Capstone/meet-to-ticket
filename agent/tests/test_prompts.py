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

    assert fields(prompts.DRAFT_USER) == {"summary", "labels", "items"}
    assert fields(prompts.REVIEW_USER) == {"summary", "source_note", "excluded", "drafts"}
    assert fields(prompts.DEDUPE_USER) == {"title", "action", "evidence", "keys", "expected"}
    # llm.py가 넘기는 값으로 format이 된다 (빠진 키나 짝이 맞지 않는 중괄호가 없음)
    prompts.DRAFT_USER.format(summary="s", labels="- 없음", items="[]")
    prompts.REVIEW_USER.format(summary="s", source_note="n", excluded="- 없음", drafts="[]")


def test_system_prompts_have_no_placeholders():
    # system 프롬프트는 format하지 않으므로 중괄호가 들어가면 그대로 모델에 전달된다
    for text in (prompts.ROWS_SYSTEM, prompts.DRAFT_SYSTEM, prompts.REVIEW_SYSTEM, prompts.DEDUPE_SYSTEM):
        assert not re.search(r"\{\w+\}", text)


def test_prompt_version_changes_with_content():
    assert re.fullmatch(r"[0-9a-f]{8}", prompts.PROMPT_VERSION)
    assert prompts.prompt_version(prompts.TEXTS) == prompts.PROMPT_VERSION
    changed = {**prompts.TEXTS, "review_system": prompts.TEXTS["review_system"] + " "}
    assert prompts.prompt_version(changed) != prompts.PROMPT_VERSION
