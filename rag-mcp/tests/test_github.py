"""github.py 테스트. 가짜 GitHub(httpx.MockTransport)로 실제 요청 없이 확인한다."""

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

import github
from fakes import FakeGitHub, gh_item


@pytest.fixture(autouse=True)
def allow_o_r(monkeypatch):
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "o/r")


def test_iter_issues_skips_prs_and_follows_pages():
    # PR은 빼고, Link 헤더를 따라 다음 페이지까지 모두 받는다
    gh = FakeGitHub({"o/r": [gh_item(1, "a"), gh_item(2, "pr", pr=True), gh_item(3, "b"), gh_item(4, "c")]})
    with gh.client() as c:
        issues = list(github.iter_issues(c, "o", "r"))
    assert [i["number"] for i in issues] == [1, 3, 4]
    assert len(gh.requests) == 2
    first = gh.requests[0].url.params
    assert first["state"] == "all" and "since" not in first


def test_iter_issues_passes_since():
    # since를 주면 그 이후에 바뀐 이슈만 요청한다
    gh = FakeGitHub({"o/r": [gh_item(1, "old", updated_at="2026-09-01T00:00:00Z"),
                             gh_item(2, "new", updated_at="2026-10-05T00:00:00Z")]})
    with gh.client() as c:
        issues = list(github.iter_issues(c, "o", "r", since="2026-10-01T00:00:00Z"))
    assert [i["number"] for i in issues] == [2]
    assert gh.requests[0].url.params["since"] == "2026-10-01T00:00:00Z"


def test_to_issue_keeps_needed_fields():
    # 열린 이슈의 closed_at(None)과 빈 본문(None)은 빈 문자열로 둔다 (Chroma 메타데이터에 None을 넣을 수 없음)
    issue = github.to_issue(gh_item(7, "제목", body=None))
    assert issue == {"number": 7, "title": "제목", "body": "", "state": "open",
                     "url": "https://github.com/o/r/issues/7", "updated_at": "2026-10-01T00:00:00Z", "closed_at": ""}


def test_check_allowed(monkeypatch):
    # 허용 목록에 없는 저장소와 빈 입력은 GitHub를 부르기 전에 막는다
    github.check_allowed("O", "R")
    with pytest.raises(ToolError, match=r"^\[REPOSITORY_NOT_ALLOWED\]"):
        github.check_allowed("x", "y")
    with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
        github.check_allowed("", "r")


def test_check_allowed_empty_list_allows_any(monkeypatch):
    # 허용 목록이 비어 있으면 검사하지 않는다 (저장소는 백엔드가 프로젝트 정보로 넘긴다)
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "")
    github.check_allowed("any", "repo")


@pytest.mark.parametrize(
    "status,headers,code",
    [
        (401, {}, "GITHUB_AUTH"),
        (403, {"x-ratelimit-remaining": "0"}, "GITHUB_RATE_LIMIT"),
        (404, {}, "GITHUB_NOT_FOUND"),
        (500, {}, "GITHUB_ERROR"),
    ],
)
def test_error_tags(status, headers, code):
    # 상태 코드별로 Agent가 읽을 [코드]가 붙는다
    transport = httpx.MockTransport(lambda r: httpx.Response(status, json={"message": "nope"}, headers=headers))
    with github.make_client(transport=transport) as c:
        with pytest.raises(ToolError, match=rf"^\[{code}\]"):
            list(github.iter_issues(c, "o", "r"))


def test_get_issue_rejects_pr():
    # 이슈 번호 자리에 PR 번호를 주면 이슈가 아니라고 알린다
    gh = FakeGitHub({"o/r": [gh_item(5, "pr", pr=True)]})
    with gh.client() as c:
        with pytest.raises(ToolError, match=r"^\[GITHUB_NOT_FOUND\]"):
            github.get_issue(c, "o", "r", 5)
