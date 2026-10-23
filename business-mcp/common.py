"""서비스(GitHub·Notion·Slack)가 함께 쓰는 설정, 오류 형식, HTTP 클라이언트, 도구 어노테이션.

설정은 business-mcp/.env에서 읽는다 (server.py가 시작할 때 load_dotenv). 값은 부를 때마다 환경 변수에서 다시 읽으므로
테스트에서 monkeypatch로 바꿀 수 있다.
"""

from __future__ import annotations

import os

import httpx
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations

# 서비스와 상관없는 오류 태그 (서비스별 태그는 각 integrations/<서비스>/api.py에 둔다)
INVALID_ARGUMENT = "INVALID_ARGUMENT"  # 도구 입력값 오류 (예: 빈 제목)

HTTP_TIMEOUT = 20.0  # 외부 API 한 번 호출의 시간 한도 (초)

# 도구 성격을 클라이언트에 알리는 힌트. 모든 도구가 외부 서비스를 부르므로 openWorldHint는 True다
READ = ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True, openWorldHint=True)
# 쓰기 도구: 같은 요청을 다시 보내면 하나 더 생긴다 (idempotent 아님)
WRITE = ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=False, openWorldHint=True)


def env(name: str, default: str = "") -> str:
    """환경 변수 값. 빈 값(예: GITHUB_TOKEN=)은 설정하지 않은 것으로 본다."""
    return os.environ.get(name, "").strip() or default


def tool_error(code: str, message: str) -> ToolError:
    """에이전트에 돌려줄 ToolError를 "[코드] 메시지" 형식으로 만든다. 에이전트는 대괄호 안의 코드를 읽는다."""
    return ToolError(f"[{code}] {message}")


def make_http_client(
    base_url: str, headers: dict[str, str], transport: httpx.BaseTransport | None = None
) -> httpx.Client:
    """외부 API용 httpx 클라이언트. transport는 테스트에서 MockTransport를 넣을 때만 쓴다."""
    return httpx.Client(base_url=base_url, headers=headers, timeout=HTTP_TIMEOUT, transport=transport)


def titled(annotations: ToolAnnotations, title: str) -> ToolAnnotations:
    """공통 어노테이션에 도구 제목만 붙인다."""
    return annotations.model_copy(update={"title": title})
