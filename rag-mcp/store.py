"""Vector DB(Chroma) 저장소.

저장소(owner/repo)마다 컬렉션을 따로 둔다. 검색할 때 다른 저장소의 이슈가 섞일 경로가 구조적으로 없다.
Chroma는 내장 모드(PersistentClient)로 CHROMA_PATH 폴더에 저장하며, 따로 띄우는 서버가 없다.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Any

import chromadb
from chromadb.config import Settings as ChromaSettings


def collection_name(owner: str, repo: str) -> str:
    """저장소별 컬렉션 이름. Chroma 이름 규칙(영문·숫자·._-, 3~512자)에 맞게 바꾼다."""
    raw = f"issues__{owner}__{repo}".lower()
    return re.sub(r"[^a-z0-9._-]", "-", raw)[:512]


class IssueStore:
    def __init__(self, path: Path):
        # anonymized_telemetry=False: 사용 통계를 외부로 보내지 않는다
        self._client = chromadb.PersistentClient(path=str(path), settings=ChromaSettings(anonymized_telemetry=False))

    def collection(self, owner: str, repo: str, model: str, dimension: int | None = None):
        """저장소 컬렉션을 연다. 없으면 cosine 거리로 새로 만든다.

        메타데이터에는 임베딩 모델·차원·마지막 색인 시각을 둔다. 검색할 때 같은 모델인지 확인하는 데 쓴다.
        """
        # 이미 있으면 그대로 연다 (메타데이터의 last_synced_at 등을 덮어쓰지 않기 위해 get_or_create를 쓰지 않음)
        if self.exists(owner, repo):
            return self.get_collection(owner, repo)
        metadata: dict[str, Any] = {"owner": owner, "repo": repo, "embedding_model": model}
        if dimension:
            metadata["dimension"] = dimension
        return self._client.create_collection(
            collection_name(owner, repo),
            configuration={"hnsw": {"space": "cosine"}},
            metadata=metadata,
            embedding_function=None,  # 임베딩은 우리가 직접 만들어 넣는다 (Chroma 기본 모델을 쓰지 않음)
        )

    def drop(self, owner: str, repo: str) -> None:
        """컬렉션을 지운다. 임베딩 모델을 바꿔 전체를 다시 색인할 때 쓴다."""
        name = collection_name(owner, repo)
        if name in {c.name for c in self._client.list_collections()}:
            self._client.delete_collection(name)

    def exists(self, owner: str, repo: str) -> bool:
        return collection_name(owner, repo) in {c.name for c in self._client.list_collections()}

    def get_collection(self, owner: str, repo: str):
        return self._client.get_collection(collection_name(owner, repo))
