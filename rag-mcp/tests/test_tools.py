"""MCP 도구와 Resource 테스트. 서버를 띄우지 않고 mcp.call_tool / read_resource로 부른다 (인메모리).

GitHub은 가짜(FakeGitHub), 임베딩은 단어 기반 가짜(KeywordEmbedder), Vector DB는 임시 폴더를 쓴다.
"""

import pytest
from mcp.server.mcpserver.exceptions import ToolError

import github
import server
from fakes import FakeGitHub, KeywordEmbedder, gh_item
from store import IssueStore


@pytest.fixture
def anyio_backend():
    return "asyncio"


ISSUES = [
    gh_item(1, "로그인 버튼을 세모로 변경", body="디자인 확정에 따라 로그인 버튼 모양을 세모로 바꾼다",
            state="closed", closed_at="2026-10-10T00:00:00Z"),
    gh_item(2, "CSV 내보내기 권한 추가", body="관리자만 CSV 내보내기를 할 수 있게 권한을 확인한다"),
    gh_item(3, "결제 실패 문구 수정", body="결제 실패 시 안내 문구를 바꾼다"),
    gh_item(4, "PR: 로그인 수정", pr=True),
]


@pytest.fixture
def env(monkeypatch, tmp_path):
    """서버의 의존성(GitHub·임베딩·Vector DB)을 가짜로 바꾼다."""
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "o/r, o/other")
    gh = FakeGitHub({"o/r": [dict(i) for i in ISSUES], "o/other": [gh_item(1, "다른 팀 로그인 버튼")]})
    emb = KeywordEmbedder()
    store = IssueStore(tmp_path / "chroma")
    real = github.make_client
    monkeypatch.setattr(github, "make_client", lambda: real(transport=__import__("httpx").MockTransport(gh.handler)))
    monkeypatch.setattr(server, "get_embedder", lambda: emb)
    monkeypatch.setattr(server, "get_store", lambda: store)
    return gh, emb, store


async def call(name, **args):
    result = await server.mcp.call_tool(name, args)
    assert not result.is_error, result.content
    return result.structured_content


# --- 도구 등록 ---


@pytest.mark.anyio
async def test_tools_and_resource_are_registered():
    # 조회 도구 1개(읽기 전용), 변경 도구 2개, 원문 Resource 1개가 보인다
    tools = {t.name: t.model_dump(by_alias=True) for t in await server.mcp.list_tools()}
    assert sorted(tools) == ["index_issue", "search_similar_issues", "sync_issues"]
    assert tools["search_similar_issues"]["annotations"]["readOnlyHint"] is True
    assert tools["sync_issues"]["annotations"]["readOnlyHint"] is False
    assert set(tools["search_similar_issues"]["inputSchema"]["required"]) == {"owner", "repo", "query"}
    assert tools["search_similar_issues"]["inputSchema"]["properties"]["state"]["enum"] == ["open", "closed", "all"]
    templates = await server.mcp.list_resource_templates()
    assert [t.uri_template for t in templates] == ["issue://{owner}/{repo}/{number}"]


# --- 검색 ---


@pytest.mark.anyio
async def test_search_finds_similar_issue_first(env):
    # 색인한 뒤 비슷한 문장으로 검색하면 그 이슈가 1위로, score가 높은 순서로 나온다
    await call("sync_issues", owner="o", repo="r")
    out = await call("search_similar_issues", owner="o", repo="r", query="로그인 버튼 네모로 변경")
    numbers = [r["number"] for r in out["results"]]
    assert numbers[0] == 1 and 4 not in numbers  # PR은 색인되지 않음
    scores = [r["score"] for r in out["results"]]
    assert scores == sorted(scores, reverse=True)
    top = out["results"][0]
    assert top["state"] == "closed" and top["closed_at"] == "2026-10-10T00:00:00Z"
    assert top["snippet"].startswith("디자인 확정에 따라")
    assert out["index"]["count"] == 3 and out["repository"] == "o/r"


@pytest.mark.anyio
async def test_search_filters_state_and_limits_k(env):
    # state=open이면 열린 이슈만, k는 최대 10으로 제한한다
    await call("sync_issues", owner="o", repo="r")
    out = await call("search_similar_issues", owner="o", repo="r", query="로그인 버튼", state="open", k=50)
    assert {r["state"] for r in out["results"]} == {"open"}
    assert len(out["results"]) == 2  # 열린 이슈 2건뿐
    assert all(r["closed_at"] is None for r in out["results"])


@pytest.mark.anyio
async def test_search_stays_in_repository(env):
    # 다른 저장소의 이슈는 검색 결과에 섞이지 않는다
    await call("sync_issues", owner="o", repo="r")
    await call("sync_issues", owner="o", repo="other")
    out = await call("search_similar_issues", owner="o", repo="r", query="로그인 버튼", k=10)
    assert all("다른 팀" not in r["title"] for r in out["results"])


@pytest.mark.anyio
async def test_search_without_index_returns_index_empty(env):
    # 아직 색인하지 않았으면 [INDEX_EMPTY]로 먼저 sync_issues를 하라고 알린다
    with pytest.raises(ToolError, match=r"\[INDEX_EMPTY\].*sync_issues"):
        await server.mcp.call_tool("search_similar_issues", {"owner": "o", "repo": "r", "query": "로그인"})


@pytest.mark.anyio
async def test_search_with_other_model_returns_index_empty(env, monkeypatch):
    # 색인과 다른 임베딩 모델이면 비교할 수 없어 다시 색인하라고 알린다
    await call("sync_issues", owner="o", repo="r")
    monkeypatch.setattr(server, "get_embedder", lambda: KeywordEmbedder(model="other-model"))
    with pytest.raises(ToolError, match=r"\[INDEX_EMPTY\].*full=true"):
        await server.mcp.call_tool("search_similar_issues", {"owner": "o", "repo": "r", "query": "로그인"})


@pytest.mark.anyio
@pytest.mark.parametrize(
    "args,code",
    [
        ({"owner": "x", "repo": "y", "query": "로그인"}, "REPOSITORY_NOT_ALLOWED"),
        ({"owner": "o", "repo": "r", "query": "  "}, "INVALID_ARGUMENT"),
    ],
)
async def test_search_input_errors(env, args, code):
    # 허용되지 않은 저장소와 빈 질문은 임베딩을 부르기 전에 막는다
    _, emb, _ = env
    with pytest.raises(ToolError, match=rf"\[{code}\]"):
        await server.mcp.call_tool("search_similar_issues", args)
    assert emb.calls == []


# --- 색인 도구 ---


@pytest.mark.anyio
async def test_sync_then_index_issue(env):
    # sync_issues로 전체를 색인하고, 새로 생긴 이슈는 index_issue로 한 건만 더한다
    gh, emb, store = env
    out = await call("sync_issues", owner="o", repo="r")
    assert (out["indexed"], out["total"]) == (3, 3)
    gh.repos["o/r"].append(gh_item(9, "로그인 버튼 색상 변경"))
    out = await call("index_issue", owner="o", repo="r", number=9)
    assert out == {"repository": "o/r", "number": 9, "indexed": True}
    assert emb.calls[-1] == ["로그인 버튼 색상 변경"]
    assert store.get_collection("o", "r").count() == 4


# --- Resource ---


@pytest.mark.anyio
async def test_issue_resource_reads_original(env):
    # 원문 Resource는 GitHub에서 바로 읽어 제목·상태·URL·본문을 돌려준다
    contents = await server.mcp.read_resource("issue://o/r/1")
    text = contents[0].content
    assert text.startswith("#1 로그인 버튼을 세모로 변경\n상태: closed (닫힌 시각 2026-10-10T00:00:00Z)")
    assert "디자인 확정에 따라" in text and contents[0].mime_type == "text/plain"


@pytest.mark.anyio
async def test_issue_resource_errors(env):
    # 없는 이슈와 허용되지 않은 저장소는 오류 태그로 알린다
    with pytest.raises(Exception, match=r"\[GITHUB_NOT_FOUND\]"):
        await server.mcp.read_resource("issue://o/r/404")
    with pytest.raises(Exception, match=r"\[REPOSITORY_NOT_ALLOWED\]"):
        await server.mcp.read_resource("issue://x/y/1")
