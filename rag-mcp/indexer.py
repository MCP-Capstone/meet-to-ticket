"""GitHub 이슈 색인 파이프라인.

이슈 읽기 → 색인 텍스트 만들기 → 임베딩 → 저장소별 컬렉션에 저장.
티켓 발행(질문)과 별개로 미리 색인해 두고, 다시 실행하면 바뀐 이슈만 임베딩한다.

다시 색인할 때 바뀐 이슈를 고르는 방법
1. 컬렉션 메타데이터의 last_synced_at 이후에 바뀐 이슈만 GitHub에서 받는다 (since).
2. 받은 이슈 중에서도 저장된 updated_at과 같은 것은 건너뛴다.
3. 바뀐 이슈가 없으면 임베딩 API를 부르지 않는다.
"""

from __future__ import annotations

from datetime import datetime, timezone
import re
from typing import Any, Callable

import httpx

import github
from embeddings import Embedder
from store import IssueStore

BODY_MAX = 2000  # 색인 텍스트에 넣는 본문 최대 길이 (긴 본문의 뒷부분은 중복 판단에 거의 쓰이지 않음)
INDEX_FORMAT_VERSION = "2"  # 템플릿 제거·댓글 포함 규칙이 바뀌면 전체 재색인을 강제한다
_MEETING_BACKGROUND = re.compile(r"(?is)<details>\s*<summary>회의 배경</summary>.*?</details>\s*")
_PUBLISHED_FOOTER = re.compile(r"(?im)^---\s*\n회의: .*?회의 후속조치 Agent가 작성\s*$")


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def build_text(issue: dict[str, Any]) -> str:
    """임베딩할 문장: 제목 + 작업 본문 + 최근 댓글.

    Agent가 발행할 때 붙이는 회의 배경·추적 footer는 모든 이슈에 반복돼 유사도를 왜곡하므로 제거한다.
    최근 댓글은 기존 이슈 연결·갱신 뒤의 변경 맥락을 반영하기 위해 index_issue에서만 붙인다.
    """
    body = (issue.get("body") or "").strip()
    body = _MEETING_BACKGROUND.sub("", body)
    body = _PUBLISHED_FOOTER.sub("", body).strip()
    comments = "\n\n".join((comment or "").strip() for comment in issue.get("comments") or [] if (comment or "").strip())
    if comments:
        body = f"{body}\n\n최근 댓글\n{comments}".strip()
    body = body[:BODY_MAX]
    return f"{issue['title'].strip()}\n\n{body}".strip()


def _metadata(issue: dict[str, Any]) -> dict[str, Any]:
    # Chroma 메타데이터에는 None을 넣을 수 없어 빈 문자열로 둔다 (closed_at은 열린 이슈면 "")
    return {k: issue[k] for k in ("number", "title", "state", "url", "updated_at", "closed_at")}


def _upsert(col, issues: list[dict[str, Any]], embedder: Embedder) -> int | None:
    """이슈들을 임베딩해 저장하고 벡터 차원을 돌려준다. 이슈 번호를 id로 써서 같은 이슈는 덮어쓴다."""
    if not issues:
        return None
    texts = [build_text(i) for i in issues]
    vectors = embedder.embed(texts)
    col.upsert(
        ids=[str(i["number"]) for i in issues],
        embeddings=vectors,
        documents=texts,
        metadatas=[_metadata(i) for i in issues],
    )
    return len(vectors[0])


def sync_issues(
    owner: str,
    repo: str,
    *,
    client: httpx.Client,
    embedder: Embedder,
    store: IssueStore,
    full: bool = False,
    now: Callable[[], str] = utc_now,
) -> dict[str, Any]:
    """저장소의 이슈를 색인한다. full=True면 컬렉션을 지우고 전체를 다시 색인한다."""
    github.check_allowed(owner, repo)

    # 다른 임베딩 모델로 만든 색인은 벡터를 비교할 수 없으므로 전체를 다시 만든다
    if not full and store.exists(owner, repo):
        existing_meta = store.get_collection(owner, repo).metadata or {}
        stored_model = existing_meta.get("embedding_model")
        if stored_model and (stored_model != embedder.model or existing_meta.get("index_format_version") != INDEX_FORMAT_VERSION):
            full = True
    if full:
        store.drop(owner, repo)

    col = store.collection(owner, repo, embedder.model)
    meta = dict(col.metadata or {})
    # GitHub에서 읽기 시작한 시각을 기록해, 읽는 도중에 바뀐 이슈도 다음 실행에서 다시 받게 한다
    started = now()
    issues = list(github.iter_issues(client, owner, repo, since=None if full else meta.get("last_synced_at")))

    stored: dict[str, str] = {}
    if issues:
        got = col.get(ids=[str(i["number"]) for i in issues], include=["metadatas"])
        stored = {id_: (m or {}).get("updated_at", "") for id_, m in zip(got["ids"], got["metadatas"])}
    changed = [i for i in issues if stored.get(str(i["number"])) != i["updated_at"]]

    dimension = _upsert(col, changed, embedder)
    meta.update({"embedding_model": embedder.model, "index_format_version": INDEX_FORMAT_VERSION, "last_synced_at": started})
    if dimension:
        meta["dimension"] = dimension
    col.modify(metadata=meta)

    return {
        "repository": f"{owner}/{repo}",
        "indexed": len(changed),
        "skipped": len(issues) - len(changed),
        "total": col.count(),
        "full": full,
        "updated_at": started,
    }


def index_issue(
    owner: str,
    repo: str,
    number: int,
    *,
    client: httpx.Client,
    embedder: Embedder,
    store: IssueStore,
) -> dict[str, Any]:
    """이슈 한 건을 바로 색인한다. Agent가 방금 발행한 이슈를 색인에 더할 때 쓴다."""
    github.check_allowed(owner, repo)
    issue = github.get_issue(client, owner, repo, number)
    issue["comments"] = github.recent_comments(client, owner, repo, number)
    col = store.collection(owner, repo, embedder.model)
    _upsert(col, [issue], embedder)
    meta = dict(col.metadata or {})
    # last_synced_at은 sync_issues만 갱신한다. 여기서 올리면 다음 증분 동기화가 그 사이에 바뀐 다른 이슈를 놓친다
    meta.update({"embedding_model": embedder.model, "index_format_version": INDEX_FORMAT_VERSION})
    col.modify(metadata=meta)
    return {"repository": f"{owner}/{repo}", "number": number, "indexed": True}


def index_status(owner: str, repo: str, *, store: IssueStore) -> dict[str, Any]:
    """프로젝트 화면용 색인 상태. 컬렉션이 없어도 오류 대신 count=0을 돌려준다."""
    github.check_allowed(owner, repo)
    if not store.exists(owner, repo):
        return {"repository": f"{owner}/{repo}", "count": 0, "last_synced_at": None, "embedding_model": None}
    col = store.get_collection(owner, repo)
    meta = col.metadata or {}
    return {
        "repository": f"{owner}/{repo}",
        "count": col.count(),
        "last_synced_at": meta.get("last_synced_at"),
        "embedding_model": meta.get("embedding_model"),
    }


def drop_index(owner: str, repo: str, *, store: IssueStore) -> dict[str, Any]:
    """해당 저장소 컬렉션만 삭제한다. 없던 색인도 성공적으로 count=0을 돌려준다."""
    github.check_allowed(owner, repo)
    existed = store.exists(owner, repo)
    store.drop(owner, repo)
    return {"repository": f"{owner}/{repo}", "deleted": existed, "count": 0}
