# 전체 서비스 Docker 실행

`compose.full.yml`은 frontend, backend, agent, mcp-server, postgres를 함께 실행한다.
기존 `docker-compose.yml`은 로컬 개발용 DB 전용 설정으로 유지한다.
전체 구성은 별도 프로젝트 이름 `meet-to-ticket-full`을 사용해 기존 DB 컨테이너와 구분한다.
Docker Compose 2.24 이상과 Docker 엔진이 필요하다. 로컬 Java·Node·Python 설치는 빌드에 필요하지 않다.

## 최초 설정

저장소 루트에서 `.env.example`을 `.env`로 복사하고 `BACKEND_INTERNAL_TOKEN`에 임의의 긴 값을 설정한다.
예를 들어 `openssl rand -hex 32`로 생성한 값을 입력한다. 이 값은 Compose가 Spring과 Agent에 동일하게 전달한다.
기존 `.env`가 있다면 덮어쓰지 않고 필요한 항목만 추가한다.

```bash
cp -n .env.example .env
```

각 서비스의 `.env.example`을 `.env`로 복사하고 키를 설정한다. 기존 파일은 그대로 사용한다.

| 파일 | 설정 |
|---|---|
| 루트 `.env` | `BACKEND_INTERNAL_TOKEN`, 공개 포트, 실행 모드, DB 및 demo 계정 설정 |
| `agent/.env` | `OPENAI_API_KEY` 및 선택적인 모델·추적 설정 |
| `mcp-server/.env` | `GITHUB_TOKEN`, `GITHUB_OWNER=MCP-Capstone`, `GITHUB_REPO=mcp-capstone` |

키 파일이 없어도 컨테이너는 기동하지만 실제 LLM 분석에는 OpenAI 키, 실제 이슈 발행에는 GitHub 토큰이 필요하다.
API 키와 토큰은 실행 시 환경 변수로 전달하며 이미지에 복사하지 않는다.

루트의 `DRY_RUN=true`가 기본이다. 분석·검수 LLM은 호출하지만 실제 GitHub 이슈는 생성하지 않는다.
`agent/.env`의 `DRY_RUN`보다 루트 Compose 설정이 우선한다.
현재 Spring은 Agent DRY_RUN의 `NOT_STARTED`를 `FAILED`로 표시하므로 발행 실패 문구가 나올 수 있다.
실제 발행은 루트 `.env`에서 `DRY_RUN=false`로 바꾸고 컨테이너를 재생성한 뒤 새 회의를 분석한다.

## 실행

기존에 직접 실행한 Spring·Vite가 8080·5173 포트를 사용 중이면 해당 실행을 종료하거나,
루트 `.env`의 `BACKEND_PORT`, `FRONTEND_PORT`를 변경한다. 기존 DB 전용 컨테이너는 그대로 둘 수 있다.

```bash
docker compose -f compose.full.yml config --quiet
docker compose -f compose.full.yml up -d --build --wait --wait-timeout 180
docker compose -f compose.full.yml ps
```

모든 서비스가 healthy이면 `http://localhost:5173`에 접속한다. 기본 demo 로그인은 `demo1` / `password1`이며
루트 `.env`의 `DEMO_LOGIN_ID`, `DEMO_PASSWORD`로 바꿀 수 있다. 기존 사용자 행의 비밀번호는 자동 변경하지 않는다.
파일 선택 창에서 `backend/examples/meetings/10min_1_summary.txt`를 업로드하고 분석을 시작한다.

컨테이너는 준비 상태를 확인한 순서로 시작한다: MCP → Agent, DB·Agent → Spring → 프론트.
Agent는 Spring이 실행될 때까지 기다리도록 설정하지 않아 진행 보고 경로의 순환 의존을 피한다.

## 연결 및 공개 범위

| 서비스 | 컨테이너 주소 | 기본 호스트 공개 |
|---|---|---|
| frontend | `frontend:80` | 5173 |
| backend | `backend:8080` | 8080 |
| agent | `agent:8001` | 없음 |
| mcp-server | `mcp-server:8000` | 없음 |
| postgres | `postgres:5432` | 없음 |

컨테이너의 `localhost`는 자기 자신이다. Compose는 Spring의 DB·Agent 주소와 Agent의 MCP·진행 보고 주소를
서비스 이름으로 설정한다. 로컬 `.env`의 localhost 주소는 컨테이너 실행 시 덮어쓴다.
Agent와 MCP 이미지 안에는 `.env` 파일을 넣지 않으므로 dotenv의 override 설정과 충돌하지 않는다.

프론트는 Vite 개발 서버 대신 Nginx로 빌드된 화면을 제공한다. `/api/`는 Spring에 전달하고 세션 쿠키를 유지한다.
브라우저에서 컨테이너 이름을 직접 사용하지 않는다. Spring은 별도로 `http://localhost:8080`에 공개한다.
`/internal/**`은 프론트 프록시 대상이 아니지만 Spring 공개 포트에서 접근 가능하므로 기존 공유 토큰 검증이 적용된다.
Agent·MCP·DB에는 호스트 포트 매핑을 만들지 않는다. 브리지 네트워크는 Agent의 OpenAI 호출과 MCP의 GitHub 호출을 허용한다.
MCP healthcheck는 TCP 기동 확인이며 GitHub 토큰의 유효성까지 검사하지 않는다.

## 로그 및 문제 확인

```bash
docker compose -f compose.full.yml logs -f --tail=100
docker compose -f compose.full.yml logs --tail=100 agent backend
```

Agent 로그에서 `POST http://backend:8080/internal/tasks/.../progress`가 202인지 확인한다.
401이면 루트 공유 토큰과 컨테이너 재생성 여부를 확인하고, 503이면 토큰 설정을 확인한다.
OpenAI 키·한도 오류나 GitHub 권한 오류는 서비스 기동 성공과 별개이므로 해당 서비스 로그를 확인한다.

환경 변수 값을 바꿨다면 `restart`가 아니라 아래처럼 재생성한다.

```bash
docker compose -f compose.full.yml up -d --force-recreate agent backend
```

## 검증

1. `ps`에서 5개 서비스의 healthy 상태를 확인한다.
2. `http://localhost:5173/api/health`와 Spring 상태 API가 정상 응답하는지 확인한다.
3. 로그인 후 샘플 파일을 업로드하고 분석한다. 진행 보고 202와 최종 항목·집계 조회를 확인한다.
4. 기본 DRY_RUN에서는 GitHub 이슈가 생성되지 않는지 확인한다. 실제 발행 검증은 별도 실행한다.
5. `ps`의 호스트 포트 매핑이 frontend·backend에만 있는지 확인한다.
6. 아래 명령으로 컨테이너를 내렸다 다시 올린 뒤 업로드한 회의가 유지되는지 확인한다.

```bash
docker compose -f compose.full.yml down
docker compose -f compose.full.yml up -d --wait --wait-timeout 180
```

`full-postgres-data` 볼륨은 컨테이너 종료·재생성 시 유지된다. 기존 DB 전용 구성의 `postgres-data`와 별개이므로
로컬 개발 DB가 이 구성으로 자동 이전되지는 않는다. 기존 로컬 데이터도 변경하지 않는다.
데이터를 유지하려면 `down -v`를 사용하지 않는다.

## 확인한 실행 결과

2026-10-21에 별도 검증 프로젝트에서 아래 내용을 확인했다.

- Agent·Spring·프론트·MCP 이미지 빌드 및 5개 서비스 healthy.
- 공개 포트는 프론트·Spring 두 개뿐이며 Agent에서 MCP 도구 목록 조회 성공.
- Nginx를 통한 로그인·회의 업로드·실제 LLM 분석·검수·최종 항목 조회 성공.
- Agent의 progress·result 알림 모두 Spring에서 202 응답.
- DRY_RUN=true로 실제 이슈 생성 없이 승인 1건을 처리. 현재 Spring 매핑에서는 발행 FAILED로 표시됨.
- 전체 컨테이너 종료·재생성 후 저장한 회의와 분석 결과 유지.
- Agent·MCP 이미지 내부에 로컬 .env 파일이 없음을 확인.

실제 GitHub 이슈 발행은 이 검증에 포함하지 않았다.

## 관련 문서

- [개발 환경 및 포트 규칙](dev-env.md)
- [Docker Compose 네트워크](https://docs.docker.com/compose/how-tos/networking/)
- [준비 상태와 기동 순서](https://docs.docker.com/compose/how-tos/startup-order/)
