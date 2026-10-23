"""meet-to-ticket RAG MCP 서버.

기존 GitHub 이슈를 저장소별 Vector DB(Chroma)에 미리 색인해 두고, Agent가 발행 전에 비슷한 이슈를 찾을 수 있게 한다.
business-mcp(이슈 생성·조회)와 역할을 나눠, 이 서버는 색인과 의미 검색만 맡는다.

도구
- search_similar_issues (조회): 의미가 비슷한 기존 이슈 찾기
- sync_issues (변경): 저장소 이슈를 색인에 반영 (바뀐 이슈만, full=true면 전체)
- index_issue (변경): 이슈 한 건을 바로 색인
Resource
- issue://{owner}/{repo}/{number}: 이슈 원문 (GitHub에서 바로 읽음)

실행: python server.py  →  http://127.0.0.1:8002/mcp (Streamable HTTP)
환경 변수: rag-mcp/.env.example 참고
"""

from __future__ import annotations

import threading
from functools import lru_cache
from pathlib import Path
from typing import Any, Literal

from dotenv import load_dotenv
from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ResourceError, ToolError
from mcp.types import ToolAnnotations

import github
import indexer
import search
from config import load_settings
from embeddings import Embedder, OpenAIEmbedder
from store import IssueStore

# 터미널에 같은 이름의 환경 변수가 있어도 rag-mcp/.env 값이 우선한다
load_dotenv(Path(__file__).resolve().parent / ".env", override=True)

# 에이전트가 http://<MCP_HOST>:<MCP_PORT>/mcp 로 연결해 아래 도구를 부른다
mcp = MCPServer("meet-to-ticket-rag")

# 도구 성격 힌트. 모든 도구가 외부 서비스(GitHub, 질문 임베딩용 OpenAI)를 부르므로 openWorldHint는 True다
READ = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=True)
# 색인 갱신: 색인을 바꾸지만(GitHub 이슈는 바꾸지 않음) 같은 요청을 반복해도 결과가 같다.
# full=true로 지워도 GitHub에서 다시 만들 수 있어 destructive로 보지 않는다
INDEX_WRITE = ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True, openWorldHint=True)


# ---------------------------------------------------------------- 의존성 (테스트에서 가짜로 바꾼다)


_store_lock = threading.Lock()


@lru_cache(maxsize=4)
def _store_at(path: str) -> IssueStore:
    # 같은 폴더의 Chroma 클라이언트를 요청마다 새로 열지 않도록 재사용한다
    return IssueStore(Path(path))


def get_store() -> IssueStore:
    # 도구는 스레드에서 실행된다. 서버를 띄운 직후 검색이 동시에 들어오면 lru_cache가 막지 못해
    # 같은 폴더의 Chroma 클라이언트를 여러 스레드가 동시에 만들다 모두 실패한다 → 처음 만들 때는 한 번에 하나만
    with _store_lock:
        return _store_at(str(load_settings().chroma_path))


def get_embedder() -> Embedder:
    s = load_settings()
    return OpenAIEmbedder(s.openai_api_key, s.embedding_model)


# ---------------------------------------------------------------- 도구


@mcp.tool(
    description=(
        "기존 GitHub 이슈 중에서 query와 의미가 비슷한 이슈를 찾는다. "
        "표현이 달라도 같은 작업인지 확인할 때 쓴다 (이슈 키나 정확한 단어로 찾을 때는 business-mcp의 search_issues). "
        "결과는 score가 높은 순서이며 score는 1에 가까울수록 비슷하다. "
        "각 결과의 state(open/closed)와 closed_at으로 진행 중인 작업인지 끝난 작업인지 구분한다. "
        "색인이 비어 있으면 [INDEX_EMPTY]를 돌려준다."
    ),
    annotations=READ.model_copy(update={"title": "유사 이슈 검색"}),
)
def search_similar_issues(
    owner: str,
    repo: str,
    query: str,
    k: int = 5,
    state: Literal["open", "closed", "all"] = "all",
) -> dict[str, Any]:
    """비슷한 이슈 최대 k개(1~10)를 돌려준다."""
    return search.search_similar(owner, repo, query, k=k, state=state, embedder=get_embedder(), store=get_store())


@mcp.tool(
    description=(
        "저장소의 GitHub 이슈를 색인(Vector DB)에 반영한다. "
        "기본은 마지막 색인 이후 바뀐 이슈만 다시 임베딩하고, full=true면 색인을 지우고 전체를 다시 만든다. "
        "Agent는 실행을 시작할 때 한 번 부른다."
    ),
    annotations=INDEX_WRITE.model_copy(update={"title": "이슈 색인 갱신"}),
)
def sync_issues(owner: str, repo: str, full: bool = False) -> dict[str, Any]:
    """색인 결과 {repository, indexed, skipped, total, full, updated_at}."""
    with github.make_client() as client:
        return indexer.sync_issues(owner, repo, client=client, embedder=get_embedder(), store=get_store(), full=full)


@mcp.tool(
    description="이슈 한 건을 바로 색인에 추가하거나 갱신한다. Agent가 방금 발행한 이슈를 색인에 더할 때 쓴다.",
    annotations=INDEX_WRITE.model_copy(update={"title": "이슈 한 건 색인"}),
)
def index_issue(owner: str, repo: str, number: int) -> dict[str, Any]:
    """색인 결과 {repository, number, indexed}."""
    with github.make_client() as client:
        return indexer.index_issue(owner, repo, number, client=client, embedder=get_embedder(), store=get_store())


# ---------------------------------------------------------------- Resource


@mcp.resource(
    "issue://{owner}/{repo}/{number}",
    name="issue",
    title="GitHub 이슈 원문",
    description="이슈의 제목, 상태(닫힌 시각), URL, 본문 전체. 검색 결과의 후보가 같은 작업인지 원문으로 확인할 때 읽는다.",
    mime_type="text/plain",
)
def issue_resource(owner: str, repo: str, number: int) -> str:
    try:
        with github.make_client() as client:
            return search.issue_text(owner, repo, int(number), client=client)
    except ToolError as exc:
        # Resource에서 ToolError가 나면 MCP가 "Error creating resource ..."로 바꿔 [코드]가 사라진다.
        # ResourceError로 바꿔 던지면 "[코드] 메시지"가 그대로 Agent에 전달된다
        raise ResourceError(str(exc)) from exc


if __name__ == "__main__":
    settings = load_settings()
    # 기본값은 로컬 전용(127.0.0.1). 다른 컨테이너에서 접속해야 하면 MCP_HOST=0.0.0.0
    mcp.run(transport="streamable-http", host=settings.host, port=settings.port)
