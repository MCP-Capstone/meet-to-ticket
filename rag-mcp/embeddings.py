"""문장을 벡터로 바꾸는 임베딩.

색인할 때와 검색할 때 반드시 같은 모델을 써야 가까운 이슈를 찾는다. 그래서 모델 이름을 컬렉션 메타데이터에 함께 저장한다.
테스트에서는 같은 인터페이스(model, embed)를 가진 가짜 임베딩을 넣어 OpenAI를 부르지 않는다.
"""

from __future__ import annotations

from typing import Protocol

from errors import CONFIG_MISSING, EMBEDDING_ERROR, tool_error

BATCH_SIZE = 100  # 한 번의 요청에 보내는 문장 수 (요청 횟수를 줄인다)


class Embedder(Protocol):
    model: str

    def embed(self, texts: list[str]) -> list[list[float]]: ...


class OpenAIEmbedder:
    def __init__(self, api_key: str, model: str):
        if not api_key:
            raise tool_error(CONFIG_MISSING, "rag-mcp/.env에 OPENAI_API_KEY를 설정해야 합니다")
        from openai import OpenAI  # 테스트 환경에서 import 비용을 줄이려고 지연 import

        self.model = model
        self._client = OpenAI(api_key=api_key)

    def embed(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), BATCH_SIZE):
            batch = texts[start : start + BATCH_SIZE]
            try:
                resp = self._client.embeddings.create(model=self.model, input=batch)
            except Exception as exc:  # noqa: BLE001 - 인증·한도·네트워크 오류를 하나의 코드로 알린다
                raise tool_error(EMBEDDING_ERROR, f"임베딩 생성 실패 ({self.model}): {exc}") from exc
            # 응답 순서가 입력 순서와 같다는 보장을 위해 index로 정렬한다
            vectors.extend(d.embedding for d in sorted(resp.data, key=lambda d: d.index))
        return vectors
