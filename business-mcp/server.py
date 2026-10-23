"""meet-to-ticket business MCP 서버 (GitHub·Notion·Slack 도구).

실행: python server.py  →  http://127.0.0.1:8000/mcp (Streamable HTTP)
환경 변수: business-mcp/.env.example 참고

서비스별 도구는 integrations/<서비스>/tools.py에 있고, 여기서는 서버를 만들어 등록하고 실행만 한다.
"""

from __future__ import annotations

from pathlib import Path

from dotenv import load_dotenv
from mcp.server.mcpserver import MCPServer

# 터미널에 같은 이름의 환경 변수가 있어도 business-mcp/.env 값이 우선한다
load_dotenv(Path(__file__).resolve().parent / ".env", override=True)

from common import env  # noqa: E402 - .env를 읽은 뒤에 설정을 쓴다
from integrations.github import tools as github_tools  # noqa: E402
from integrations.notion import tools as notion_tools  # noqa: E402
from integrations.slack import tools as slack_tools  # noqa: E402

# 에이전트가 http://<MCP_HOST>:<MCP_PORT>/mcp 로 연결해 아래 도구를 부른다
mcp = MCPServer("meet-to-ticket")
github_tools.register(mcp)
notion_tools.register(mcp)
slack_tools.register(mcp)


if __name__ == "__main__":
    # 기본값은 로컬 전용(127.0.0.1). 다른 컨테이너에서 접속해야 하면 MCP_HOST=0.0.0.0
    mcp.run(transport="streamable-http", host=env("MCP_HOST", "127.0.0.1"), port=int(env("MCP_PORT", "8000")))
