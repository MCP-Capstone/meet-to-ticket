# 기술 스택

## 구성

```
frontend → backend → agent → mcp-server → GitHub (이슈 생성)
              │           │
              │           └→ rag-mcp → Chroma (기존 이슈 색인/유사 검색)
              ├→ PostgreSQL
              └→ RabbitMQ (분석 큐)
```

| 디렉토리 | 역할 |
|---|---|
| `frontend/` | 웹 프론트 |
| `backend/` | 프론트와 연결되는 API 서버 |
| `agent/` | 회의록 분석 및 티켓 생성 흐름을 담당하는 AI Agent |
| `mcp-server/` | 티켓 생성 등 외부 연동 도구를 제공하는 MCP 서버 |
| `rag-mcp/` | 기존 이슈를 색인하고 유사 이슈를 검색하는 MCP 서버 |

## 확정

| 영역 | 스택 |
|---|---|
| Frontend | React, TypeScript, Vite |
| Backend | Java 21, Spring Boot 4.1, Gradle, Spring Data JPA |
| DB | PostgreSQL |
| 메시지 큐 | RabbitMQ (분석 접수 큐, 실패 시 DLQ) |
| 실행 | Docker Compose (`compose.full.yml`) |

## AI (agent, mcp-server, rag-mcp)

- Agent는 FastAPI를 사용한다.
- rag-mcp는 Python MCP 서버이며 Chroma와 OpenAI 임베딩(`text-embedding-3-small`)을 사용한다.
- 그 외 세부 스택은 AI 담당자가 결정한다.

## 결정된 사항

- 티켓 생성 대상 서비스: GitHub Issues
- 회의록 입력 형태: `.txt` 파일 업로드
- 배포 / 로컬 실행 환경: Docker Compose (`compose.full.yml`)
