"""meet-to-ticket RAG MCP 서버.

기존 GitHub 이슈를 저장소별 Vector DB(Chroma)에 미리 색인해 두고, Agent가 발행 전에 비슷한 이슈를 찾을 수 있게 한다.
mcp-server(이슈 생성·조회)와 역할을 나눠, 이 서버는 색인과 의미 검색만 맡는다.

실행: python server.py  →  http://127.0.0.1:8002/mcp (Streamable HTTP)
환경 변수: rag-mcp/.env.example 참고
"""

from __future__ import annotations

from pathlib import Path

from dotenv import load_dotenv
from mcp.server.mcpserver import MCPServer

from config import load_settings

# 터미널에 같은 이름의 환경 변수가 있어도 rag-mcp/.env 값이 우선한다
load_dotenv(Path(__file__).resolve().parent / ".env", override=True)

# 에이전트가 http://<MCP_HOST>:<MCP_PORT>/mcp 로 연결해 도구를 부른다.
# 검색·색인 도구와 이슈 원문 Resource는 다음 작업에서 추가한다.
mcp = MCPServer("meet-to-ticket-rag")


if __name__ == "__main__":
    settings = load_settings()
    # 기본값은 로컬 전용(127.0.0.1). 다른 컨테이너에서 접속해야 하면 MCP_HOST=0.0.0.0
    mcp.run(transport="streamable-http", host=settings.host, port=settings.port)
