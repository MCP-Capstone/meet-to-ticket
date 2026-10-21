import datetime as dt
from pathlib import Path


def test_empty_env_values_use_defaults(monkeypatch):
    from config import load_settings

    monkeypatch.setenv("OPENAI_MODEL", "")
    monkeypatch.setenv("OPENAI_REVIEW_MODEL", "")
    monkeypatch.setenv("MCP_SERVER_URL", "")
    monkeypatch.setenv("OPENAI_REASONING_EFFORT", "")
    for name in ("LLM_TIMEOUT_SECONDS", "TASK_DEADLINE_SECONDS", "MAX_SOURCE_CHARS"):
        monkeypatch.setenv(name, "")
    limits = load_settings()
    assert (limits.llm_timeout_seconds, limits.task_deadline_seconds, limits.max_source_chars) == (120, 600, 40000)
    monkeypatch.setenv("OPENAI_REVIEW_REASONING_EFFORT", "")
    s = load_settings()
    assert s.openai_model == "gpt-5.4-nano"
    assert s.review_model == "gpt-5.4-mini"
    assert s.reasoning_effort == "none"  # 추출·초안은 추론을 끄고 temperature 0
    assert s.review_reasoning_effort == ""  # 검수는 모델 기본 추론
    assert s.mcp_server_url == "http://127.0.0.1:8000/mcp"
    # 검수 모델은 추출·초안 모델을 따라가지 않는다
    monkeypatch.setenv("OPENAI_MODEL", "gpt-5.4-mini")
    assert load_settings().review_model == "gpt-5.4-mini"
    monkeypatch.setenv("OPENAI_MODEL", "gpt-5.4")
    assert load_settings().review_model == "gpt-5.4-mini"


def test_backend_request_sample_parses():
    from schemas import RunRequest

    path = Path(__file__).resolve().parent.parent / "samples" / "requests" / "csv_check.json"
    req = RunRequest.model_validate_json(path.read_text(encoding="utf-8"))
    assert req.task_id == "9b12ec5a-1423-4b71-8e20-f8f28d306c81"
    assert req.meeting.meeting_id == 2 and req.meeting.meeting_date == dt.date(2026, 10, 22)
    assert req.project.github_owner == "MCP-Capstone" and req.project.github_repo == "mcp-capstone"
    assert req.project_members[0].github_name == "0unk0"


def test_result_is_camel_case():
    from schemas import RunResult

    out = RunResult(task_id="t", status="SUCCEEDED", stage="DONE", items=[]).model_dump(by_alias=True)
    assert set(out) >= {"taskId", "status", "stage", "errorCode", "errorMessage", "summary", "items"}


def test_backend_request_sample_to_initial_state():
    from runner import initial_state
    from schemas import RunRequest

    path = Path(__file__).resolve().parent.parent / "samples" / "requests" / "csv_check.json"
    state = initial_state(RunRequest.model_validate_json(path.read_text(encoding="utf-8")))
    assert state["task_id"] == "9b12ec5a-1423-4b71-8e20-f8f28d306c81"
    assert state["meeting_id"] == "2" and state["meeting_date"] == "2026-10-22"
    assert state["owner"] == "MCP-Capstone" and state["repo"] == "mcp-capstone"
    assert state["members"][0]["github_name"] == "0unk0"
