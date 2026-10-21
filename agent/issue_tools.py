"""중복 판단에 쓰는 기존 이슈 조회 도구.

모델에게 주는 도구(조회만)와 코드만 부르는 도구(색인)를 나눈다.
- 모델용: search_similar_issues(rag-mcp, 의미 검색), search_issues(mcp-server, 키·단어 검색),
          read_issue(rag-mcp Resource issue://{owner}/{repo}/{number}를 도구로 감쌈)
- 코드용: sync(실행마다 1회 색인 갱신), index(발행한 이슈 색인)

저장소(owner/repo)는 백엔드 요청 값으로 고정한다. 모델용 도구의 인자에는 없어서 모델이 바꿀 수 없다.
도구 결과는 길이를 잘라 넘기고, 오류는 예외 대신 "[코드] 메시지" 문자열로 돌려줘 모델이 보고 판단하게 한다.
이슈 생성(create_issue)은 여기에 없다. 발행은 판단 결과에 따라 코드가 한다.
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any, Awaitable, Callable, Literal

from pydantic import BaseModel, Field

from publisher import ToolCallError, error_from_exception, parse_tool_result

log = logging.getLogger("agent.issue_tools")

State = Literal["open", "closed", "all"]
ResourceReader = Callable[[str], Awaitable[str]]


class SimilarArgs(BaseModel):
    query: str = Field(description="찾을 작업 내용. 새 티켓의 제목이나 한 줄 요약")
    k: int = Field(5, ge=1, le=10, description="가져올 후보 수 (1~10)")
    state: State = Field("all", description="이슈 상태 (open, closed, all)")


class KeywordArgs(BaseModel):
    query: str = Field(description="이슈 키(예: AUTH-112)나 정확히 일치해야 하는 단어")
    state: State = Field("all", description="이슈 상태 (open, closed, all)")
    limit: int = Field(5, ge=1, le=20, description="가져올 이슈 수 (1~20)")


class ReadArgs(BaseModel):
    number: int = Field(ge=1, description="읽을 이슈 번호 (검색 결과의 number)")


def _error_text(err: ToolCallError) -> str:
    """모델에게 주는 오류 문구: "[코드] 메시지"만 남긴다 (MCP의 "Error executing tool …" 앞부분은 뺌)."""
    found = re.search(r"\[[A-Z][A-Z0-9_]+\]\s*(.*)", err.message, re.S)
    return f"[{err.code}] {found.group(1) if found else err.message}"[:500]


def _clip(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 20] + "\n…(길어서 생략됨)"


class IssueTools:
    def __init__(
        self,
        owner: str,
        repo: str,
        *,
        similar: Any | None = None,      # rag-mcp search_similar_issues (MCP 도구)
        keyword: Any | None = None,      # mcp-server search_issues (MCP 도구)
        reader: ResourceReader | None = None,  # rag-mcp Resource 읽기
        sync_tool: Any | None = None,    # rag-mcp sync_issues
        index_tool: Any | None = None,   # rag-mcp index_issue
        max_chars: int = 2000,
    ):
        self.owner = owner
        self.repo = repo
        self._similar = similar
        self._keyword = keyword
        self._reader = reader
        self._sync = sync_tool
        self._index = index_tool
        self.max_chars = max_chars

    # ------------------------------------------------------------ 모델용

    async def _call(self, tool: Any, args: dict[str, Any]) -> dict[str, Any]:
        try:
            raw = await tool.ainvoke({"owner": self.owner, "repo": self.repo, **args})
        except Exception as exc:  # noqa: BLE001
            raise error_from_exception(exc) from exc
        return parse_tool_result(raw)

    def _text(self, data: Any) -> str:
        return _clip(json.dumps(data, ensure_ascii=False), self.max_chars)

    async def search_similar_issues(self, query: str, k: int = 5, state: State = "all") -> str:
        try:
            out = await self._call(self._similar, {"query": query, "k": min(max(k, 1), 10), "state": state})
        except ToolCallError as exc:
            if exc.code == "INDEX_EMPTY":  # 아직 색인된 이슈가 없음 = 후보 없음
                return self._text({"results": [], "note": "색인된 이슈가 없음"})
            return _error_text(exc)
        results = [
            {
                "number": r.get("number"),
                "title": r.get("title"),
                "state": r.get("state"),
                "closed_at": r.get("closed_at"),
                "score": round(float(r.get("score") or 0), 3),
                "snippet": (r.get("snippet") or "")[:200],
            }
            for r in out.get("results", [])
        ]
        return self._text({"results": results})

    async def search_issues(self, query: str, state: State = "all", limit: int = 5) -> str:
        try:
            out = await self._call(self._keyword, {"query": query, "state": state, "limit": min(max(limit, 1), 20)})
        except ToolCallError as exc:
            return _error_text(exc)
        issues = [
            {k: i.get(k) for k in ("number", "title", "state", "closed_at")}
            for i in out.get("issues", [])
        ]
        return self._text({"issues": issues})

    async def read_issue(self, number: int) -> str:
        uri = f"issue://{self.owner}/{self.repo}/{number}"
        try:
            text = await self._reader(uri)
        except Exception as exc:  # noqa: BLE001
            return _error_text(error_from_exception(exc))
        return _clip(text, self.max_chars)

    def judge_tools(self) -> list[Any]:
        """모델에게 줄 조회 도구. 서버에 없는 도구는 빼고 준다."""
        from langchain_core.tools import StructuredTool

        tools = []
        if self._similar is not None:
            tools.append(StructuredTool.from_function(
                coroutine=self.search_similar_issues, name="search_similar_issues", args_schema=SimilarArgs,
                description="의미가 비슷한 기존 이슈를 찾는다. 이슈 키가 없거나 표현이 달라도 같은 작업일 수 있을 때 쓴다. "
                            "결과의 score는 1에 가까울수록 비슷하다."))
        if self._keyword is not None:
            tools.append(StructuredTool.from_function(
                coroutine=self.search_issues, name="search_issues", args_schema=KeywordArgs,
                description="이슈 키(예: AUTH-112)나 정확한 단어로 기존 이슈를 찾는다. 회의록에 관련 키가 있을 때 먼저 쓴다."))
        if self._reader is not None:
            tools.append(StructuredTool.from_function(
                coroutine=self.read_issue, name="read_issue", args_schema=ReadArgs,
                description="후보 이슈의 제목·상태·본문·닫힌 시각을 원문으로 읽는다. 검색 결과만으로 같은 작업인지 애매할 때 쓴다."))
        return tools

    # ------------------------------------------------------------ 코드용

    async def sync(self) -> dict[str, Any] | None:
        """색인을 저장소의 최신 상태로 맞춘다 (바뀐 이슈만). 실패하면 None."""
        if self._sync is None:
            return None
        try:
            return await self._call(self._sync, {"full": False})
        except ToolCallError as exc:
            log.warning("색인 갱신 실패 %s/%s: [%s] %s", self.owner, self.repo, exc.code, exc.message)
            return None

    async def index(self, number: int) -> bool:
        """새로 발행한 이슈를 색인에 추가한다. 실패해도 발행 결과에는 영향이 없다."""
        if self._index is None:
            return False
        try:
            await self._call(self._index, {"number": number})
            return True
        except ToolCallError as exc:
            log.warning("이슈 색인 실패 %s/%s#%s: [%s] %s", self.owner, self.repo, number, exc.code, exc.message)
            return False
