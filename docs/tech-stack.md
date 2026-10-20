# 기술 스택

## 구성

```
frontend → backend → agent → mcp-server → 외부 서비스 (티켓 생성)
              │
              └→ PostgreSQL
```

| 디렉토리 | 역할 |
|---|---|
| `frontend/` | 웹 프론트 |
| `backend/` | 프론트와 연결되는 API 서버 |
| `agent/` | 회의록 분석 및 티켓 생성 흐름을 담당하는 AI Agent |
| `mcp-server/` | 티켓 생성 등 외부 연동 도구를 제공하는 MCP 서버 |

## 확정

| 영역 | 스택 |
|---|---|
| Frontend | React, TypeScript |
| Backend | Java 21, Spring Boot 3, Gradle, Spring Data JPA |
| DB | PostgreSQL |

## AI (agent, mcp-server)

- Agent는 FastAPI를 사용한다.
- 그 외 세부 스택은 AI 담당자가 결정한다.

## 미정

- 티켓 생성 대상 서비스 (GitHub Issues, Jira, Notion 등)
- 회의록 입력 형태 (텍스트, 음성)
- 배포 / 로컬 실행 환경
