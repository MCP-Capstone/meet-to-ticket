# meet-to-ticket

회의록에서 할 일을 추출해 티켓(GitHub Issue)으로 만드는 서비스

## 구성

| 디렉토리 | 역할 | 스택 |
|---|---|---|
| `frontend/` | 웹 프론트 | React, TypeScript, Vite |
| `backend/` | API 서버 | Java 21, Spring Boot 3.5, JPA |
| `agent/` | 회의록 분석 AI Agent | Python, FastAPI |
| `mcp-server/` | 티켓 생성 MCP 서버 | Python, MCP |

## 로컬 DB (PostgreSQL)

Docker Desktop을 켠 상태에서 루트에서 실행한다.

```bash
docker compose up -d      # 실행
docker compose ps         # 상태 확인 (healthy면 정상)
docker compose down       # 종료 (데이터 유지)
docker compose down -v    # 종료 + 데이터 삭제
```

| 항목 | 값 |
|---|---|
| 주소 | `localhost:5432` |
| DB | `meettoticket` |
| 사용자 / 비밀번호 | `meettoticket` / `meettoticket` |

### 서비스별 접속 정보

| 서비스 | 설정 |
|---|---|
| backend | `application.yml` 기본값 사용 (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`로 변경 가능) |
| agent, mcp-server | `DATABASE_URL=postgresql://meettoticket:meettoticket@localhost:5432/meettoticket` |

- 모든 서비스는 로컬(호스트)에서 실행하므로 주소는 `localhost:5432`로 같다.
- 테이블 구조는 backend(JPA 엔티티)가 관리한다. agent, mcp-server에서 테이블을 만들거나 구조를 바꾸지 않는다.
- 값을 바꾸려면 `.env.example`을 `.env`로 복사해서 수정한다.
- 5432 포트를 이미 쓰는 로컬 PostgreSQL이 있으면 먼저 종료한다.

## 문서

- `docs/tech-stack.md`: 기술 스택
- `docs/convention.md`: 브랜치 / 커밋 / PR 규칙
- `docs/dev-env.md`: 포트, CORS, 실행 순서
