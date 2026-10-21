"""github.py 테스트. httpx.MockTransport로 GitHub 응답을 흉내 내며, 실제 요청은 보내지 않는다."""

import json

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

import github


def client_with(handler):
    """handler(request) -> httpx.Response 로 응답하는 가짜 GitHub 클라이언트."""
    return github.make_client(transport=httpx.MockTransport(handler))


def issue_json(number=7, assignees=()):
    """GitHub 이슈 생성 응답 중 래퍼가 읽는 필드만 담은 예시."""
    return {
        "number": number,
        "html_url": f"https://github.com/o/r/issues/{number}",
        "assignees": [{"login": a} for a in assignees],
    }


# --- 허용 저장소 검사 ---


def test_check_allowed(monkeypatch):
    # 쉼표 구분 목록, 앞뒤 공백, 대소문자 차이를 모두 허용한다
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "MCP-Capstone/mcp-capstone, a/b")
    github.check_allowed("MCP-Capstone", "mcp-capstone")
    github.check_allowed("A", "B")
    with pytest.raises(ToolError, match=r"^\[REPOSITORY_NOT_ALLOWED\]"):
        github.check_allowed("other", "repo")


def test_check_allowed_empty_list(monkeypatch):
    # 허용 목록이 비어 있으면 어떤 저장소도 쓸 수 없다
    for name in ("GITHUB_ALLOWED_REPOS", "GITHUB_OWNER", "GITHUB_REPO"):
        monkeypatch.delenv(name, raising=False)
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        github.check_allowed("o", "r")


def test_check_allowed_from_owner_repo(monkeypatch):
    # GITHUB_ALLOWED_REPOS가 없으면 GITHUB_OWNER/GITHUB_REPO 하나만 허용한다
    monkeypatch.delenv("GITHUB_ALLOWED_REPOS", raising=False)
    monkeypatch.setenv("GITHUB_OWNER", "MCP-Capstone")
    monkeypatch.setenv("GITHUB_REPO", "mcp-capstone")
    github.check_allowed("MCP-Capstone", "mcp-capstone")
    with pytest.raises(ToolError, match="REPOSITORY_NOT_ALLOWED"):
        github.check_allowed("MCP-Capstone", "meet-to-ticket")


# --- 이슈 생성 ---


def test_create_issue_success():
    # 요청 경로·본문이 GitHub API 형식에 맞고, 제목 앞뒤 공백은 제거된다
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        return httpx.Response(201, json=issue_json(12, ["alice"]))

    with client_with(handler) as c:
        out = github.create_issue(c, "o", "r", " 제목 ", "본문", ["mtt"], ["alice"])
    assert seen["path"] == "/repos/o/r/issues"
    assert seen["body"] == {"title": "제목", "body": "본문", "labels": ["mtt"], "assignees": ["alice"]}
    assert out["status"] == "created"
    assert out["number"] == 12
    assert out["url"].endswith("/12")
    assert out["dropped_assignees"] == []
    assert out["retried_without_assignees"] is False


def test_create_issue_silently_dropped_assignee():
    # GitHub가 오류 없이 담당자만 빼고 만든 경우에도 dropped_assignees로 알린다
    def handler(request):
        return httpx.Response(201, json=issue_json(3, []))

    with client_with(handler) as c:
        out = github.create_issue(c, "o", "r", "t", "b", None, ["ghost"])
    assert out["dropped_assignees"] == ["ghost"]


def test_create_issue_retries_without_assignees_on_422():
    # 담당자 때문에 422가 나면 담당자를 빼고 한 번 더 요청한다
    calls = []

    def handler(request):
        body = json.loads(request.content)
        calls.append(body)
        if "assignees" in body:
            return httpx.Response(
                422,
                json={"message": "Validation Failed", "errors": [{"field": "assignees", "code": "invalid"}]},
            )
        return httpx.Response(201, json=issue_json(5))

    with client_with(handler) as c:
        out = github.create_issue(c, "o", "r", "t", "b", None, ["nobody"])
    assert len(calls) == 2
    assert "assignees" not in calls[1]
    assert out["retried_without_assignees"] is True
    assert out["dropped_assignees"] == ["nobody"]


# --- 오류 태그 ---


# 상태 코드별로 에이전트가 읽을 [코드]가 맞게 붙는지 확인한다
@pytest.mark.parametrize(
    "status,headers,code",
    [
        (401, {}, "GITHUB_AUTH"),
        (403, {}, "GITHUB_AUTH"),
        (403, {"x-ratelimit-remaining": "0"}, "GITHUB_RATE_LIMIT"),
        (429, {}, "GITHUB_RATE_LIMIT"),
        (404, {}, "GITHUB_NOT_FOUND"),
        (422, {}, "GITHUB_VALIDATION"),
        (500, {}, "GITHUB_ERROR"),
    ],
)
def test_error_tags(status, headers, code):
    def handler(request):
        return httpx.Response(status, json={"message": "nope"}, headers=headers)

    with client_with(handler) as c:
        with pytest.raises(ToolError, match=rf"^\[{code}\] GitHub API {status}"):
            github.create_issue(c, "o", "r", "t", "b")


def test_empty_title_rejected():
    # 공백뿐인 제목은 GitHub를 부르기 전에 거부한다
    with client_with(lambda r: httpx.Response(201, json=issue_json())) as c:
        with pytest.raises(ToolError, match="INVALID_ARGUMENT"):
            github.create_issue(c, "o", "r", "  ", "b")


def test_token_header(monkeypatch):
    # GITHUB_TOKEN이 Bearer 인증 헤더로 실린다
    monkeypatch.setenv("GITHUB_TOKEN", "abc")
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("authorization")
        return httpx.Response(201, json=issue_json())

    with client_with(handler) as c:
        github.create_issue(c, "o", "r", "t", "b")
    assert seen["auth"] == "Bearer abc"


# --- 이슈 목록 조회 ---


def test_list_issues_excludes_prs_and_limits():
    # PR은 빼고, 요청한 state와 개수 제한이 GitHub 요청에 반영된다
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json=[
            {"number": 3, "title": "a", "state": "open", "html_url": "u3", "assignees": [{"login": "x"}]},
            {"number": 2, "title": "pr", "state": "open", "html_url": "u2", "pull_request": {}},
            {"number": 1, "title": "b", "state": "open", "html_url": "u1", "assignees": []},
        ])

    with client_with(handler) as c:
        out = github.list_issues(c, "o", "r", "all", 5)
    assert seen["path"] == "/repos/o/r/issues"
    assert seen["params"]["state"] == "all"
    assert seen["params"]["per_page"] == "10"
    assert [i["number"] for i in out] == [3, 1]
    assert out[0] == {"number": 3, "title": "a", "state": "open", "url": "u3", "assignees": ["x"]}


def test_list_issues_limit_cut():
    # PR을 빼고 남은 이슈가 limit보다 많으면 limit개만 돌려준다
    items = [{"number": n, "title": "t", "state": "open", "html_url": "u"} for n in range(5, 0, -1)]
    with client_with(lambda r: httpx.Response(200, json=items)) as c:
        out = github.list_issues(c, "o", "r", limit=2)
    assert [i["number"] for i in out] == [5, 4]


def test_list_issues_bad_state():
    # state는 open, closed, all만 받는다
    with client_with(lambda r: httpx.Response(200, json=[])) as c:
        with pytest.raises(ToolError, match="INVALID_ARGUMENT"):
            github.list_issues(c, "o", "r", "weird")


def test_list_issues_error_tag():
    # 조회 실패도 생성과 같은 에러 태그로 돌려준다
    with client_with(lambda r: httpx.Response(404, json={"message": "Not Found"})) as c:
        with pytest.raises(ToolError, match=r"^\[GITHUB_NOT_FOUND\]"):
            github.list_issues(c, "o", "r")
