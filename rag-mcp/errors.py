"""도구 오류 형식.

실패는 "[코드] 메시지" 형식의 ToolError로 돌려준다. Agent는 대괄호 안의 코드를 읽어 원인을 구분한다.
코드 이름은 mcp-server와 같은 것을 쓰고, 이 서버에만 있는 코드(INDEX_EMPTY, EMBEDDING_ERROR)를 더한다.
"""

from __future__ import annotations

from mcp.server.mcpserver.exceptions import ToolError

CONFIG_MISSING = "CONFIG_MISSING"  # .env에 필요한 값(토큰·키·저장소)이 없음
INVALID_ARGUMENT = "INVALID_ARGUMENT"  # 도구 입력값 오류
REPOSITORY_NOT_ALLOWED = "REPOSITORY_NOT_ALLOWED"  # 허용 목록에 없는 저장소
GITHUB_AUTH = "GITHUB_AUTH"  # 401/403: 토큰이 없거나 권한 부족
GITHUB_NOT_FOUND = "GITHUB_NOT_FOUND"  # 404: 저장소·이슈가 없거나 토큰으로 볼 수 없음
GITHUB_RATE_LIMIT = "GITHUB_RATE_LIMIT"  # 429 또는 남은 호출 수 0인 403
GITHUB_ERROR = "GITHUB_ERROR"  # 그 밖의 GitHub 실패 (5xx 등)
INDEX_EMPTY = "INDEX_EMPTY"  # 색인이 비어 있어 검색할 수 없음
EMBEDDING_ERROR = "EMBEDDING_ERROR"  # 임베딩 생성 실패 (OpenAI 오류)


def tool_error(code: str, message: str) -> ToolError:
    """에이전트에 돌려줄 ToolError를 "[코드] 메시지" 형식으로 만든다."""
    return ToolError(f"[{code}] {message}")
