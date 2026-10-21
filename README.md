# meet-to-ticket

회의록에서 할 일을 추출해 티켓(GitHub Issue)으로 만드는 서비스

## 동작 방식

```
로그인 -> 프로젝트 선택 -> .txt 회의록 업로드 -> 분석 시작
 -> (Agent) 할 일 추출 -> 담당자/기한 정리 -> 검수(최대 2회 보완)
 -> 검수를 통과한 항목만 GitHub Issue로 발행
 -> 처리 중 화면이 상태를 조회 -> 항목별 결과와 이슈 링크 표시
```

```
브라우저 -> frontend(5173) -/api 프록시-> backend(8080) -> agent(8001) -> mcp-server(8000) -> GitHub
```

- backend는 PostgreSQL(5432)과 RabbitMQ(5672, 분석 접수 큐)를 사용한다.
- agent는 중복 판단에 rag-mcp(8002, Chroma 색인)를 사용한다. 꺼져 있어도 발행은 진행된다.

## 구성

| 디렉토리 | 역할 | 스택 | 포트 |
|---|---|---|---|
| `frontend/` | 웹 프론트 | React, TypeScript, Vite | 5173 |
| `backend/` | API 서버 | Java 21, Spring Boot 4.1, JPA | 8080 |
| `agent/` | 회의록 분석 AI Agent | Python 3.12, FastAPI, LangGraph | 8001 |
| `mcp-server/` | 이슈 생성 MCP 서버 | Python 3.12, MCP | 8000 |
| `rag-mcp/` | 기존 이슈 색인/유사 검색 MCP 서버 | Python 3.12, MCP, Chroma | 8002 |

## 실행 방법

### 전체 서비스를 Docker로 실행

이 구성은 demo 프로파일과 기본 테스트 계정을 사용하는 로컬·데모용이며, 운영 배포용 구성이 아닙니다.

Docker Desktop만으로 7개 서비스(프론트/Spring/Agent/MCP/RAG MCP/DB/RabbitMQ)를 함께 실행할 수 있다. 환경 변수 초기 설정 후:

```bash
docker compose -f compose.full.yml up -d --build
```

초기 설정·포트·로그·종료·검증 방법은 [전체 Docker 실행 안내](docs/docker-compose.md)를 참고한다.
아래 내용은 각 서비스를 직접 실행하는 개발 방식이다.

### 준비물

| 항목 | 용도 |
|---|---|
| Docker Desktop | PostgreSQL, RabbitMQ |
| Java 21 | backend |
| Node 24 권장 (`frontend/.nvmrc`), 최소 20.19 또는 22.12 이상 | frontend |
| Python 3.12, [uv](https://docs.astral.sh/uv/) | agent, mcp-server |
| OpenAI API 키 | agent (회의록 분석) |
| GitHub 토큰 (이슈 쓰기 권한) | mcp-server (이슈 발행) |

이슈 발행 테스트는 `MCP-Capstone/mcp-capstone` 저장소에서 한다. 토큰은 이 저장소에 이슈를 만들 수 있어야 한다.

### 1. 환경 변수 파일 만들기

각 폴더의 `.env.example`을 복사해 `.env`로 만들고 값을 채운다. `.env`는 커밋하지 않는다.

| 파일 | 채울 값 |
|---|---|
| `mcp-server/.env` | `GITHUB_TOKEN` (`GITHUB_OWNER=MCP-Capstone`, `GITHUB_REPO=mcp-capstone`) |
| `agent/.env` | `OPENAI_API_KEY` (`BACKEND_BASE_URL`은 비워 둔다. 기본 `DRY_RUN=true`라 이슈는 만들지 않고, 실제로 발행하려면 `false`로 바꾼다) |
| 루트 `.env` | 필요 없음 (기본값 사용) |

### 2. 서비스 실행 (터미널 5개, 이 순서대로)

```bash
# 1) PostgreSQL + RabbitMQ (루트)
docker compose up -d

# 2) MCP 서버
cd mcp-server && uv venv --python 3.12 && uv pip sync requirements-dev.txt
python server.py                       # http://127.0.0.1:8000/mcp

# 3) Agent
cd agent && uv venv --python 3.12 && uv pip sync requirements-dev.txt
uvicorn main:app --port 8001

# 4) Backend (demo 시드 + 실제 Agent 연결)
cd backend
AGENT_MODE=live ./gradlew bootRun --args='--spring.profiles.active=demo'

# 5) Frontend
cd frontend && npm ci
VITE_USE_MOCK=false npm run dev        # http://localhost:5173
```

가상환경을 활성화한 뒤(`source .venv/bin/activate`) 2), 3)을 실행한다. 자세한 설치 방법은 각 폴더의 README를 본다.

### 3. 사용해 보기

1. http://localhost:5173 접속 후 로그인한다. demo 시드 계정은 `demo1` / `password1`이다.
2. 프로젝트(`MCP-Capstone/mcp-capstone`)에서 `backend/examples/meetings/`의 `.txt` 샘플 회의록을 업로드한다.
3. 분석을 시작한다. 처리 중 화면에서 진행 상태를 보고, 끝나면 항목별 검수/발행 결과와 이슈 링크가 나온다.

### Agent 없이 화면만 보기

OpenAI/GitHub 키 없이 흐름만 확인하려면 PostgreSQL/RabbitMQ(`docker compose up -d`), backend, frontend만 실행한다. 이슈는 만들어지지 않고 모의 결과가 나온다.

```bash
docker compose up -d
cd backend && ./gradlew bootRun --args='--spring.profiles.active=demo'   # AGENT_MODE 기본값 mock
cd frontend && npm ci && VITE_USE_MOCK=false npm run dev
```

백엔드도 없이 화면만 보려면 `cd frontend && npm ci && npm run dev` (가짜 API, 기본값).

### 테스트

| 대상 | 명령 |
|---|---|
| backend | `cd backend && ./gradlew test` |
| agent | `cd agent && pytest` |
| mcp-server | `cd mcp-server && pytest` |
| rag-mcp | `cd rag-mcp && pytest` |
| frontend | `cd frontend && npm run build` (타입 검사 포함) |

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

- 모든 서비스는 로컬(호스트)에서 실행하므로 주소는 `localhost:5432`로 같다.
- agent, mcp-server, rag-mcp는 DB를 사용하지 않는다. 테이블 구조는 backend(JPA 엔티티)가 관리한다.
- 값을 바꾸려면 `.env.example`을 `.env`로 복사해서 수정한다.
- 5432 포트를 이미 쓰는 로컬 PostgreSQL이 있으면 먼저 종료한다.

## 문서

- `docs/tech-stack.md`: 기술 스택
- `docs/convention.md`: 브랜치 / 커밋 / PR 규칙
- `docs/dev-env.md`: 포트, CORS, 실행 순서
- `backend/README.md`: API, demo 프로필, 실제 Agent 연결
- `agent/README.md`, `mcp-server/README.md`, `rag-mcp/README.md`: 개발환경과 실행
- `docs/docker-compose.md`: 전체 Docker 실행 안내
- `docs/api/README.md`: API 문서
- `docs/rabbitmq-analysis.md`: RabbitMQ 분석 큐
- `scripts/db/README.md`: 테이블, 시드 데이터, 샘플 회의록
