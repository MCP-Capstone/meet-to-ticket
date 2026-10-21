"""유사 이슈 검색과 이슈 원문.

검색: 질문 문장을 색인과 같은 모델로 임베딩한 뒤, 그 저장소 컬렉션에서만 cosine 거리가 가까운 이슈를 찾는다.
원문: 색인이 아니라 GitHub에서 바로 읽는다 (항상 최신 상태·본문).
"""

from __future__ import annotations

from typing import Any

import httpx

import github
from embeddings import Embedder
from errors import INDEX_EMPTY, INVALID_ARGUMENT, tool_error
from store import IssueStore

K_MAX = 10  # 한 번에 돌려주는 최대 개수 (모델 입력이 길어지지 않게)
SNIPPET_MAX = 200  # 결과마다 붙이는 본문 앞부분 길이
RESOURCE_BODY_MAX = 20000  # 원문 Resource 본문 최대 길이
STATES = ("open", "closed", "all")


def _snippet(document: str, title: str) -> str:
    """색인 텍스트(제목 + 본문)에서 제목을 빼고 본문 앞부분만 남긴다."""
    body = document[len(title):] if document.startswith(title) else document
    body = " ".join(body.split())  # 줄바꿈·연속 공백 정리
    return body[:SNIPPET_MAX]


def search_similar(
    owner: str,
    repo: str,
    query: str,
    *,
    k: int,
    state: str,
    embedder: Embedder,
    store: IssueStore,
) -> dict[str, Any]:
    """query와 의미가 가까운 기존 이슈를 score가 높은 순서로 돌려준다. score = 1 - cosine 거리."""
    github.check_allowed(owner, repo)
    query = (query or "").strip()
    if not query:
        raise tool_error(INVALID_ARGUMENT, "query가 비어 있습니다")
    if state not in STATES:
        raise tool_error(INVALID_ARGUMENT, "state는 open, closed, all 중 하나입니다")
    k = max(1, min(int(k), K_MAX))

    if not store.exists(owner, repo) or store.get_collection(owner, repo).count() == 0:
        raise tool_error(INDEX_EMPTY, f"{owner}/{repo}에 색인된 이슈가 없습니다. 먼저 sync_issues를 실행하세요")
    col = store.get_collection(owner, repo)
    meta = col.metadata or {}
    # 색인과 다른 모델로 만든 질문 벡터는 비교할 수 없다
    if meta.get("embedding_model") and meta["embedding_model"] != embedder.model:
        raise tool_error(
            INDEX_EMPTY,
            f"색인 모델({meta['embedding_model']})과 현재 모델({embedder.model})이 달라 검색할 수 없습니다. "
            "sync_issues(full=true)로 다시 색인하세요",
        )

    vector = embedder.embed([query])[0]
    found = col.query(
        query_embeddings=[vector],
        n_results=min(k, col.count()),
        where=None if state == "all" else {"state": state},
        include=["metadatas", "documents", "distances"],
    )
    results = []
    for m, doc, dist in zip(found["metadatas"][0], found["documents"][0], found["distances"][0]):
        results.append({
            "number": m["number"],
            "title": m["title"],
            "state": m["state"],
            "url": m["url"],
            "score": round(1 - dist, 4),
            "snippet": _snippet(doc or "", m["title"]),
            "closed_at": m.get("closed_at") or None,
        })
    return {
        "repository": f"{owner}/{repo}",
        "index": {"model": meta.get("embedding_model"), "count": col.count(), "updated_at": meta.get("last_synced_at")},
        "results": results,
    }


def issue_text(owner: str, repo: str, number: int, *, client: httpx.Client) -> str:
    """이슈 원문을 사람이 읽기 좋은 텍스트로 만든다 (Resource issue://{owner}/{repo}/{number})."""
    github.check_allowed(owner, repo)
    i = github.get_issue(client, owner, repo, number)
    state = i["state"] + (f" (닫힌 시각 {i['closed_at']})" if i["closed_at"] else "")
    body = i["body"].strip() or "(본문 없음)"
    if len(body) > RESOURCE_BODY_MAX:
        body = body[:RESOURCE_BODY_MAX] + "\n\n(본문이 길어 일부 생략됨)"
    return f"#{i['number']} {i['title']}\n상태: {state}\nURL: {i['url']}\n\n{body}\n"
