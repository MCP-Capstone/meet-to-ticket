"""integrations/github/api.py 테스트. httpx.MockTransport로 GitHub 응답을 흉내 내며, 실제 요청은 보내지 않는다."""

import json

import httpx
import pytest
from mcp.server.mcpserver.exceptions import ToolError

from integrations.github import api as github


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


def test_check_allowed_empty_list_allows_any(monkeypatch):
    # 허용 목록이 비어 있으면 검사하지 않는다 (저장소는 백엔드가 프로젝트 정보로 넘긴다)
    # GITHUB_OWNER/GITHUB_REPO는 더 이상 허용 목록으로 쓰지 않는다
    monkeypatch.delenv("GITHUB_ALLOWED_REPOS", raising=False)
    monkeypatch.setenv("GITHUB_OWNER", "MCP-Capstone")
    monkeypatch.setenv("GITHUB_REPO", "mcp-capstone")
    github.check_allowed("MCP-Capstone", "meet-to-ticket")


def test_check_allowed_rejects_blank():
    # owner나 repo가 비어 있으면 GitHub를 부르기 전에 막는다
    with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
        github.check_allowed(" ", "r")


# --- 이슈 생성 ---


def labels_json(*names):
    """GitHub 라벨 목록 응답."""
    return [{"name": n, "description": f"{n} 설명"} for n in names]


def test_create_issue_success():
    # 요청 경로·본문이 GitHub API 형식에 맞고, 제목 앞뒤 공백은 제거된다
    seen = {}

    def handler(request):
        if request.method == "GET":
            return httpx.Response(200, json=labels_json("mtt"))
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
    assert out["dropped_labels"] == []


def test_create_issue_drops_unknown_labels():
    # 저장소에 없는 라벨은 GitHub에 보내지 않고(새로 만들어지지 않게) dropped_labels로 알린다.
    # 대소문자가 달라도 저장소 표기로 맞춰 보내고, 같은 라벨이 두 번 와도 한 번만 보낸다
    seen = {}

    def handler(request):
        if request.method == "GET":
            return httpx.Response(200, json=labels_json("Backlog", "bug"))
        seen["body"] = json.loads(request.content)
        return httpx.Response(201, json=issue_json(3))

    with client_with(handler) as c:
        out = github.create_issue(c, "o", "r", "t", "b", ["backlog", "BACKLOG", "새라벨", "bug", "새라벨"])
    assert seen["body"]["labels"] == ["Backlog", "bug"]
    assert out["dropped_labels"] == ["새라벨"]


def test_create_issue_all_labels_unknown():
    # 요청한 라벨이 모두 없으면 labels 없이 만든다
    seen = {}

    def handler(request):
        if request.method == "GET":
            return httpx.Response(200, json=[])
        seen["body"] = json.loads(request.content)
        return httpx.Response(201, json=issue_json(4))

    with client_with(handler) as c:
        out = github.create_issue(c, "o", "r", "t", "b", ["없음"])
    assert "labels" not in seen["body"]
    assert out["dropped_labels"] == ["없음"]


def test_create_issue_without_labels_skips_label_lookup():
    # 라벨을 요청하지 않으면 라벨 목록을 조회하지 않는다
    methods = []

    def handler(request):
        methods.append(request.method)
        return httpx.Response(201, json=issue_json(5))

    with client_with(handler) as c:
        out = github.create_issue(c, "o", "r", "t", "b", ["", "  "])
    assert methods == ["POST"]
    assert out["dropped_labels"] == []


def test_list_labels_follows_pages():
    # 라벨이 많으면 Link 헤더의 다음 페이지까지 모두 받는다
    calls = []

    def handler(request):
        calls.append(str(request.url))
        if "page=2" in str(request.url):
            return httpx.Response(200, json=labels_json("c"))
        next_url = "https://api.github.com/repos/o/r/labels?per_page=100&page=2"
        return httpx.Response(200, json=labels_json("a", "b"), headers={"Link": f'<{next_url}>; rel="next"'})

    with client_with(handler) as c:
        out = github.list_labels(c, "o", "r")
    assert [label["name"] for label in out] == ["a", "b", "c"]
    assert out[0] == {"name": "a", "description": "a 설명"}
    assert "per_page=100" in calls[0] and len(calls) == 2


def test_list_labels_error_tag():
    # 라벨 조회 실패도 같은 에러 태그로 돌려준다 (이슈 생성 전에 실패하면 이슈는 만들어지지 않음)
    with client_with(lambda r: httpx.Response(404, json={"message": "Not Found"})) as c:
        with pytest.raises(ToolError, match=r"^\[GITHUB_NOT_FOUND\]"):
            github.create_issue(c, "o", "r", "t", "b", ["bug"])


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
    assert out["dropped_assignees"] == ["nobody"]


# --- 기존 이슈 댓글·갱신 ---


def test_add_comment_posts_to_target_issue():
    # SAME 처리의 댓글은 해당 이슈에만 쓰고, 생성된 댓글의 ID·URL을 돌려준다.
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        seen["body"] = json.loads(request.content)
        return httpx.Response(201, json={"id": 33, "html_url": "https://github.com/o/r/issues/7#issuecomment-33"})

    with client_with(handler) as c:
        out = github.add_comment(c, "o", "r", 7, " 연결 근거 ")
    assert seen == {"path": "/repos/o/r/issues/7/comments", "body": {"body": "연결 근거"}}
    assert out == {"status": "commented", "comment_id": 33, "url": "https://github.com/o/r/issues/7#issuecomment-33"}


@pytest.mark.parametrize("number,body", [(0, "x"), (-1, "x"), (True, "x"), (1, "  ")])
def test_add_comment_rejects_invalid_input(number, body):
    with client_with(lambda r: httpx.Response(201, json={})) as c:
        with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
            github.add_comment(c, "o", "r", number, body)


def test_update_issue_appends_section_and_only_adds_valid_labels():
    # PATCH body는 교체형이므로 현재 본문을 먼저 읽어 보존하고, 기존 라벨에 유효한 라벨만 더한다.
    calls = []

    def handler(request):
        calls.append((request.method, request.url.path, json.loads(request.content) if request.content else None))
        if request.method == "GET" and request.url.path == "/repos/o/r/issues/7":
            return httpx.Response(200, json={"number": 7, "body": "기존 본문", "labels": [{"name": "bug"}]})
        if request.method == "GET" and request.url.path == "/repos/o/r/labels":
            return httpx.Response(200, json=labels_json("bug", "enhancement"))
        if request.method == "PATCH":
            return httpx.Response(200, json={"number": 7, "html_url": "https://github.com/o/r/issues/7"})
        raise AssertionError(f"unexpected request: {request.method} {request.url.path}")

    with client_with(handler) as c:
        out = github.update_issue(c, "o", "r", 7, "## 회의 변경 사항\n- 범위 확대", ["ENHANCEMENT", "unknown"])
    assert calls == [
        ("GET", "/repos/o/r/issues/7", None),
        ("GET", "/repos/o/r/labels", None),
        ("PATCH", "/repos/o/r/issues/7", {"body": "기존 본문\n\n## 회의 변경 사항\n- 범위 확대", "labels": ["bug", "enhancement"]}),
    ]
    assert out == {
        "status": "updated", "number": 7, "url": "https://github.com/o/r/issues/7", "dropped_labels": ["unknown"],
        "comment_id": None, "comment_url": None, "comment_error": None,
    }


def test_update_issue_adds_note_after_patch_and_keeps_update_if_comment_fails():
    # 갱신 댓글은 PATCH가 끝난 다음 쓴다. 댓글만 실패해도 본문 갱신 성공 결과를 돌려준다.
    calls = []

    def handler(request):
        calls.append(request.method)
        if request.method == "GET":
            return httpx.Response(200, json={"number": 7, "body": None, "labels": []})
        if request.method == "PATCH":
            return httpx.Response(200, json={"number": 7, "html_url": "u"})
        if request.method == "POST":
            return httpx.Response(403, json={"message": "no permission"})
        raise AssertionError("unexpected request")

    with client_with(handler) as c:
        out = github.update_issue(c, "o", "r", 7, "## 회의 변경 사항", note="갱신했습니다")
    assert calls == ["GET", "PATCH", "POST"]
    assert out["status"] == "updated"
    assert out["comment_id"] is None
    assert out["comment_error"].startswith("[GITHUB_AUTH]")


def test_update_issue_rejects_pr_and_oversized_combined_body():
    with client_with(lambda r: httpx.Response(200, json={"pull_request": {}})) as c:
        with pytest.raises(ToolError, match="PR"):
            github.update_issue(c, "o", "r", 7, "## 변경")

    with client_with(lambda r: httpx.Response(200, json={"body": "a" * github.BODY_MAX})) as c:
        with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
            github.update_issue(c, "o", "r", 7, "## 변경")


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


# --- 이슈 키워드 검색 ---


def search_item(number, title, state="open", repo="o/r", pr=False, closed_at=None):
    """GitHub 검색 API 응답의 이슈 한 건."""
    item = {
        "number": number,
        "title": title,
        "state": state,
        "html_url": f"https://github.com/{repo}/issues/{number}",
        "repository_url": f"https://api.github.com/repos/{repo}",
        "closed_at": closed_at,
    }
    if pr:
        item["pull_request"] = {}
    return item


def test_search_issues_scopes_query_to_repository():
    # 검색어 뒤에 저장소 범위(repo:), 이슈만(is:issue), 상태(state:)를 코드가 붙인다
    seen = {}

    def handler(request):
        seen["path"] = request.url.path
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, json={"items": [search_item(112, "AUTH-112 로그인 개선", "closed", closed_at="c")]})

    with client_with(handler) as c:
        out = github.search_issues(c, "o", "r", "AUTH-112", state="closed", limit=3)
    assert seen["path"] == "/search/issues"
    assert seen["params"]["q"] == "AUTH-112 repo:o/r is:issue state:closed"
    assert seen["params"]["per_page"] == "3"
    assert out == [{"number": 112, "title": "AUTH-112 로그인 개선", "state": "closed",
                    "url": "https://github.com/o/r/issues/112", "closed_at": "c"}]


def test_search_issues_removes_scope_qualifiers():
    # 검색어에 repo:·org:·is: 같은 범위 지정이 섞여 와도 지우고 우리 저장소만 검색한다
    # (GitHub 검색은 repo:를 여러 개 주면 OR로 묶어 다른 저장소까지 찾는다)
    seen = {}

    def handler(request):
        seen["q"] = request.url.params["q"]
        return httpx.Response(200, json={"items": []})

    with client_with(handler) as c:
        github.search_issues(c, "o", "r", "로그인 repo:evil/x org:evil is:pr -repo:a/b")
    assert seen["q"] == "로그인 repo:o/r is:issue"


def test_search_issues_filters_other_repos_and_prs():
    # 응답에 다른 저장소나 PR이 섞여 오면 한 번 더 걸러 낸다
    items = [search_item(1, "a"), search_item(2, "pr", pr=True), search_item(3, "other", repo="x/y"), search_item(4, "b")]
    with client_with(lambda r: httpx.Response(200, json={"items": items})) as c:
        out = github.search_issues(c, "o", "r", "a")
    assert [i["number"] for i in out] == [1, 4]


@pytest.mark.parametrize("query,state", [("", "all"), ("repo:x/y", "all"), ("a", "weird")])
def test_search_issues_invalid_input(query, state):
    # 비어 있거나 범위 지정만 있는 검색어, 잘못된 state는 GitHub를 부르기 전에 막는다
    with client_with(lambda r: httpx.Response(200, json={"items": []})) as c:
        with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
            github.search_issues(c, "o", "r", query, state=state)


def test_search_issues_limit_and_errors():
    # 개수는 최대 20개로 제한하고, 검색 API 호출 한도 초과는 [GITHUB_RATE_LIMIT]로 알린다
    seen = {}

    def ok(request):
        seen["per_page"] = request.url.params["per_page"]
        return httpx.Response(200, json={"items": []})

    with client_with(ok) as c:
        github.search_issues(c, "o", "r", "a", limit=100)
    assert seen["per_page"] == "20"
    limited = lambda r: httpx.Response(403, json={"message": "rate"}, headers={"x-ratelimit-remaining": "0"})
    with client_with(limited) as c:
        with pytest.raises(ToolError, match=r"^\[GITHUB_RATE_LIMIT\]"):
            github.search_issues(c, "o", "r", "a")


# --- 멤버 저장소 권한 확인 ---


def test_check_member_permissions():
    # 저장소를 먼저 확인하고, 멤버마다 assignees API로 지정 가능 여부를 본다 (204 가능, 404 불가, 그 밖 확인 실패)
    paths = []

    def handler(request):
        paths.append(request.url.path)
        if request.url.path == "/repos/o/r":
            return httpx.Response(200, json={"full_name": "o/r"})
        user = request.url.path.rsplit("/", 1)[-1]
        status = {"alice": 204, "bob": 404}.get(user, 500)
        return httpx.Response(status)

    with client_with(handler) as c:
        out = github.check_member_permissions(c, "o", "r", [" alice ", "bob", "carol", "ALICE", ""])
    assert out == [
        {"github_name": "alice", "permission": "GRANTED"},
        {"github_name": "bob", "permission": "DENIED"},
        {"github_name": "carol", "permission": "ERROR"},
    ]
    # 같은 계정(대소문자 무시)과 빈 값은 한 번만, 저장소 확인은 먼저 한 번
    assert paths == ["/repos/o/r", "/repos/o/r/assignees/alice", "/repos/o/r/assignees/bob", "/repos/o/r/assignees/carol"]


def test_check_member_permissions_repo_not_visible():
    # 저장소를 볼 수 없으면 모든 멤버가 "권한 없음"으로 보이므로, 멤버별 결과 없이 오류로 돌려준다
    with client_with(lambda r: httpx.Response(404, json={"message": "Not Found"})) as c:
        with pytest.raises(ToolError, match=r"^\[GITHUB_NOT_FOUND\]"):
            github.check_member_permissions(c, "o", "r", ["alice"])


def test_check_member_permissions_connection_error_is_member_error():
    # 멤버 한 명의 확인 중 연결이 끊기면 그 멤버만 ERROR (권한 없음으로 보지 않는다)
    def handler(request):
        if request.url.path == "/repos/o/r":
            return httpx.Response(200, json={})
        if request.url.path.endswith("/flaky"):
            raise httpx.ReadTimeout("timeout", request=request)
        return httpx.Response(204)

    with client_with(handler) as c:
        out = github.check_member_permissions(c, "o", "r", ["alice", "flaky"])
    assert out == [
        {"github_name": "alice", "permission": "GRANTED"},
        {"github_name": "flaky", "permission": "ERROR"},
    ]


def test_check_member_permissions_empty_and_limit():
    # 확인할 계정이 없으면 GitHub를 부르지 않고, 너무 많으면 거부한다
    with client_with(lambda r: httpx.Response(500)) as c:
        assert github.check_member_permissions(c, "o", "r", ["", "  "]) == []
        with pytest.raises(ToolError, match=r"^\[INVALID_ARGUMENT\]"):
            github.check_member_permissions(c, "o", "r", [f"u{i}" for i in range(101)])
