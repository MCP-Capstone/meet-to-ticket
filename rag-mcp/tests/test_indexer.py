"""색인 파이프라인 테스트. 가짜 GitHub·가짜 임베딩과 임시 Chroma 폴더를 쓴다."""

import pytest
from mcp.server.mcpserver.exceptions import ToolError

import indexer
from errors import EMBEDDING_ERROR, tool_error
from fakes import FakeEmbedder, FakeGitHub, gh_item
from store import IssueStore, collection_name


@pytest.fixture(autouse=True)
def allow_repos(monkeypatch):
    monkeypatch.setenv("GITHUB_ALLOWED_REPOS", "o/r, o/other")


@pytest.fixture
def store(tmp_path):
    return IssueStore(tmp_path / "chroma")


def clock(*times):
    """부를 때마다 다음 시각을 돌려주는 가짜 시계."""
    it = iter(times)
    return lambda: next(it)


def sync(gh, emb, store, owner="o", repo="r", **kw):
    with gh.client() as c:
        return indexer.sync_issues(owner, repo, client=c, embedder=emb, store=store, **kw)


def test_first_sync_indexes_all_issues(store):
    # 처음에는 PR을 뺀 이슈를 모두 임베딩해 저장하고, 모델·차원·색인 시각을 남긴다
    gh = FakeGitHub({"o/r": [gh_item(1, "로그인 버튼 세모로 변경"), gh_item(2, "PR", pr=True), gh_item(3, "CSV 내보내기")]})
    emb = FakeEmbedder()
    out = sync(gh, emb, store, now=clock("2026-10-21T00:00:00Z"))
    assert out == {"repository": "o/r", "indexed": 2, "skipped": 0, "total": 2, "full": False,
                   "updated_at": "2026-10-21T00:00:00Z"}
    col = store.get_collection("o", "r")
    assert col.metadata["embedding_model"] == "fake-embed"
    assert col.metadata["dimension"] == 4
    assert col.metadata["last_synced_at"] == "2026-10-21T00:00:00Z"
    got = col.get(ids=["1"], include=["metadatas", "documents"])
    assert got["metadatas"][0]["title"] == "로그인 버튼 세모로 변경"
    assert got["documents"][0].startswith("로그인 버튼 세모로 변경")


def test_second_sync_without_changes_does_not_embed(store):
    # 바뀐 이슈가 없으면 임베딩 API를 부르지 않는다
    gh = FakeGitHub({"o/r": [gh_item(1, "a", updated_at="2026-10-01T00:00:00Z")]})
    emb = FakeEmbedder()
    sync(gh, emb, store, now=clock("2026-10-21T00:00:00Z"))
    out = sync(gh, emb, store, now=clock("2026-10-22T00:00:00Z"))
    assert out["indexed"] == 0 and out["total"] == 1
    assert len(emb.calls) == 1  # 첫 실행에서만 임베딩
    # 두 번째 실행은 마지막 색인 시각 이후에 바뀐 이슈만 요청한다
    assert gh.requests[-1].url.params["since"] == "2026-10-21T00:00:00Z"


def test_changed_issue_is_reindexed(store):
    # 제목이 바뀌거나 닫힌 이슈만 다시 임베딩하고 상태도 갱신한다
    items = [gh_item(1, "a", updated_at="2026-10-01T00:00:00Z"), gh_item(2, "b", updated_at="2026-10-01T00:00:00Z")]
    gh = FakeGitHub({"o/r": items})
    emb = FakeEmbedder()
    sync(gh, emb, store, now=clock("2026-10-21T00:00:00Z"))
    items[1].update(title="b 수정", state="closed", closed_at="2026-10-21T09:00:00Z", updated_at="2026-10-21T09:00:00Z")
    out = sync(gh, emb, store, now=clock("2026-10-22T00:00:00Z"))
    assert out["indexed"] == 1 and out["total"] == 2
    assert emb.calls[-1] == ["b 수정"]
    meta = store.get_collection("o", "r").get(ids=["2"], include=["metadatas"])["metadatas"][0]
    assert (meta["state"], meta["closed_at"]) == ("closed", "2026-10-21T09:00:00Z")


def test_repositories_are_kept_apart(store):
    # 저장소마다 컬렉션이 따로라 다른 저장소의 이슈가 섞이지 않는다
    gh = FakeGitHub({"o/r": [gh_item(1, "a")], "o/other": [gh_item(1, "다른 팀"), gh_item(2, "다른 팀 2")]})
    emb = FakeEmbedder()
    sync(gh, emb, store)
    sync(gh, emb, store, repo="other")
    assert store.get_collection("o", "r").count() == 1
    assert store.get_collection("o", "other").count() == 2
    assert collection_name("O", "R") == collection_name("o", "r")


def test_full_sync_rebuilds_collection(store):
    # full=True면 기존 색인을 지우고 GitHub에 있는 이슈로 다시 만든다 (삭제된 이슈도 정리됨)
    items = [gh_item(1, "a"), gh_item(2, "b")]
    gh = FakeGitHub({"o/r": items})
    emb = FakeEmbedder()
    sync(gh, emb, store)
    del items[1]
    out = sync(gh, emb, store, full=True)
    assert out["full"] is True and out["total"] == 1


def test_model_change_forces_full_sync(store):
    # 다른 임베딩 모델로 만든 색인은 비교할 수 없으므로 자동으로 전체를 다시 색인한다
    gh = FakeGitHub({"o/r": [gh_item(1, "a")]})
    sync(gh, FakeEmbedder("model-a"), store)
    out = sync(gh, FakeEmbedder("model-b"), store)
    assert out["full"] is True and out["indexed"] == 1
    assert store.get_collection("o", "r").metadata["embedding_model"] == "model-b"


def test_not_allowed_repository(store):
    # 허용되지 않은 저장소는 GitHub를 부르기 전에 막는다
    gh = FakeGitHub({"x/y": [gh_item(1, "a")]})
    with pytest.raises(ToolError, match=r"^\[REPOSITORY_NOT_ALLOWED\]"):
        sync(gh, FakeEmbedder(), store, owner="x", repo="y")
    assert gh.requests == []


def test_embedding_error_is_reported(store):
    # 임베딩이 실패하면 [EMBEDDING_ERROR]로 알리고 색인 시각을 남기지 않는다 (다음 실행에서 다시 시도)
    class Broken(FakeEmbedder):
        def embed(self, texts):
            raise tool_error(EMBEDDING_ERROR, "boom")

    gh = FakeGitHub({"o/r": [gh_item(1, "a")]})
    with pytest.raises(ToolError, match=r"^\[EMBEDDING_ERROR\]"):
        sync(gh, Broken(), store)
    assert "last_synced_at" not in (store.get_collection("o", "r").metadata or {})


def test_index_issue_adds_one(store):
    # 방금 만든 이슈 한 건을 바로 색인에 더한다
    gh = FakeGitHub({"o/r": [gh_item(1, "a"), gh_item(9, "새 이슈")]})
    emb = FakeEmbedder()
    out = indexer.index_issue("o", "r", 9, client=gh.client(), embedder=emb, store=store)
    assert out == {"repository": "o/r", "number": 9, "indexed": True}
    assert store.get_collection("o", "r").count() == 1


def test_build_text_limits_body():
    # 본문은 앞 2,000자만 쓴다
    text = indexer.build_text({"title": "t", "body": "x" * 5000})
    assert text.startswith("t\n\n") and len(text) == 3 + indexer.BODY_MAX
