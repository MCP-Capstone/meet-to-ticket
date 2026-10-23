import json

import pytest

from publisher import ToolCallError, build_issue_body, error_from_exception, parse_tool_result

RESULT = {"status": "created", "number": 3, "url": "u"}


@pytest.mark.parametrize(
    "raw",
    [
        json.dumps(RESULT),
        RESULT,
        [{"type": "text", "text": json.dumps(RESULT)}],
        ([{"type": "text", "text": json.dumps(RESULT)}], None),
        json.dumps({"result": RESULT}),
    ],
)
def test_parse_tool_result_shapes(raw):
    assert parse_tool_result(raw)["number"] == 3


def test_parse_tool_result_error_text():
    with pytest.raises(ToolCallError) as e:
        parse_tool_result("Error executing tool create_issue: [REPOSITORY_NOT_ALLOWED] x/y는 허용되지 않은 저장소입니다")
    assert e.value.code == "REPOSITORY_NOT_ALLOWED"


def test_error_from_exception():
    assert error_from_exception(Exception("Error executing tool create_issue: [GITHUB_AUTH] 401")).code == "GITHUB_AUTH"
    assert error_from_exception(Exception("ReadTimeout")).ambiguous is True
    assert error_from_exception(Exception("All connection attempts failed")).code == "MCP_UNAVAILABLE"
    assert error_from_exception(Exception("weird")).code == "PUBLISH_FAILED"


def test_build_issue_body():
    ticket = {
        "work_key": "10m1-a1",
        "description": "설명",
        "acceptance_criteria": ["조건1"],
        "evidence_quotes": ["근거 문장"],
        "due": {"text": "2026-10-20 오전", "date": "2026-10-20", "time": "12:00"},
        "assignee_names": ["박서연"],
        "assignees": ["seoyeon-p"],
        "unresolved_assignees": [],
        "expected": {"raw": "기존 이슈 갱신 (AUTH-112)", "kind": "update", "keys": ["AUTH-112"]},
        "open_questions": ["질문"],
    }
    body = build_issue_body(ticket, "10min_1", "배경 문단")
    for part in ["## 작업 내용\n설명", "## 완료 조건\n- 조건1", "> 근거 문장", "2026-10-20 12:00 (원문: 2026-10-20 오전)",
                 "@seoyeon-p", "## 관련 이슈", "관련 키: AUTH-112", "## 확인 필요",
                 "<summary>회의 배경</summary>", "회의: 10min_1 · work_key: 10m1-a1"]:
        assert part in body
    assert "## 상세 작업" not in body and "## 상위 이슈" not in body  # 값이 없는 칸은 생략


class _Resp:
    def __init__(self, status_code):
        self.status_code = status_code
        self.text = ""


class _Client:
    def __init__(self):
        self.posts = []

    async def post(self, url, json=None, headers=None):
        self.posts.append((url, json, headers))
        return _Resp(200)


@pytest.mark.anyio
async def test_reporter_skips_without_base_url():
    from reporter import BackendReporter

    client = _Client()
    reporter = BackendReporter("", client=client)
    assert reporter.enabled is False
    assert await reporter.result("t1", {"status": "SUCCEEDED"}) is False
    assert client.posts == []


@pytest.mark.anyio
async def test_reporter_posts_progress_and_result():
    from reporter import BackendReporter

    client = _Client()
    reporter = BackendReporter("http://backend/", token="secret", client=client)
    await reporter.progress("t1", "RUNNING", "REVIEWING")
    assert await reporter.result("t1", {"status": "SUCCEEDED"}) is True
    (url1, body1, headers1), (url2, _, _) = client.posts
    assert url1 == "http://backend/internal/tasks/t1/progress"
    assert body1 == {"status": "RUNNING", "stage": "REVIEWING"}
    assert headers1 == {"X-Internal-Token": "secret"}
    assert url2 == "http://backend/internal/tasks/t1/result"
