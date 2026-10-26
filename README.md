# meet-to-ticket

회의록에서 할 일을 추출해 티켓으로 만들고 GitHub Issue, Notion 페이지, Slack 공지로 발행하는 서비스

## 동작 방식

```
로그인 -> 프로젝트 선택 -> .txt 회의록 업로드 -> 분석 시작
 -> (Agent) 할 일 추출 -> 담당자/기한 정리 -> 검수(최대 2회 보완)
 -> 검수를 통과한 항목을 티켓으로 저장 (발행 대상 제안: GitHub / Notion / Slack)
 -> 담당자가 카드에서 고치고 확인 -> 확인한 티켓만 일괄 발행
 -> 결과 화면이 상태를 조회 -> 항목별 결과와 대상별 링크 표시
```

발행 대상은 티켓마다 Agent가 제안하고 담당자가 바꿀 수 있다. 대상별 필드, 권한, 재발행 규칙은 [발행 대상별 매핑](docs/publish-targets.md)을 본다.

| 발행 대상 | 만드는 것 | 현재 상태 |
|---|---|---|
| GitHub | Issue 신규 생성, 기존 Issue에 연결(댓글)/갱신(본문 섹션 추가) | 일괄 발행 구현 |
| Notion | 프로젝트 기획 페이지 아래 '회의 후속 티켓' 폴더에 페이지 생성 | 일괄 발행 구현 (신규 생성만) |
| Slack | 프로젝트 채널에 회의 결과 공지 메시지 1건 | 일괄 발행 구현 (신규 게시만). Notion/Slack은 결과 불명 뒤 담당자 확인 결과를 남겨야 재발행 가능. 알려진 한계는 매핑 문서 참고 |

```
브라우저 -> frontend(5173) -/api 프록시-> backend(8080) -> agent(8001) -> business-mcp(8000) -> GitHub / Notion / Slack
```

- backend는 PostgreSQL(5432)과 RabbitMQ(5672, 분석 접수 큐)를 사용한다.
- agent는 중복 판단에 rag-mcp(8002, Chroma 색인)를 사용한다. 꺼져 있어도 발행은 진행된다.

## 구성

| 디렉토리 | 역할 | 스택 | 포트 |
|---|---|---|---|
| `frontend/` | 웹 프론트 | React, TypeScript, Vite | 5173 |
| `backend/` | API 서버 | Java 21, Spring Boot 4.1, JPA | 8080 |
| `agent/` | 회의록 분석 AI Agent | Python 3.12, FastAPI, LangGraph | 8001 |
| `business-mcp/` | GitHub, Notion, Slack 발행 MCP 서버 | Python 3.12, MCP | 8000 |
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
| Python 3.12, [uv](https://docs.astral.sh/uv/) | agent, business-mcp |
| OpenAI API 키 | agent (회의록 분석) |
| GitHub 토큰 (이슈 쓰기 권한) | business-mcp (이슈 발행) |
| Notion Integration 토큰, Slack 봇 토큰 (선택) | business-mcp (Notion/Slack 발행. 필요 권한은 [매핑 문서](docs/publish-targets.md)) |

이슈 발행 테스트는 `MCP-Capstone/mcp-capstone` 저장소에서 한다. 토큰은 이 저장소에 이슈를 만들 수 있어야 한다.

### 1. 환경 변수 파일 만들기

각 폴더의 `.env.example`을 복사해 `.env`로 만들고 값을 채운다. `.env`는 커밋하지 않는다.

| 파일 | 채울 값 |
|---|---|
| `business-mcp/.env` | `GITHUB_TOKEN` (로컬 테스트는 `GITHUB_ALLOWED_REPOS=MCP-Capstone/mcp-capstone` 권장) |
| `agent/.env` | `OPENAI_API_KEY` (`BACKEND_BASE_URL`은 비워 둔다. 기본 `DRY_RUN=true`라 이슈는 만들지 않고, 실제로 발행하려면 `false`로 바꾼다) |
| 루트 `.env` | 필요 없음 (기본값 사용) |

### 2. 서비스 실행 (터미널 5개, 이 순서대로)

```bash
# 1) PostgreSQL + RabbitMQ (루트)
docker compose up -d

# 2) MCP 서버
cd business-mcp && uv venv --python 3.12 && uv pip sync requirements-dev.txt
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
| business-mcp | `cd business-mcp && pytest` |
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
- agent, business-mcp, rag-mcp는 DB를 사용하지 않는다. 테이블 구조는 backend(JPA 엔티티)가 관리한다.
- 값을 바꾸려면 `.env.example`을 `.env`로 복사해서 수정한다.
- DB/RabbitMQ 포트는 `127.0.0.1`로만 열린다. 원격 호스트/VM의 Compose에는 다른 PC에서 직접 접속할 수 없고, RabbitMQ 관리 화면(15672)도 localhost 전용이다.
- `./gradlew bootRun`은 `local` 프로파일로 실행되어 기본 자격증명을 허용한다. 프로파일 없이 jar로 실행하면 기본 자격증명/빈 내부 토큰은 기동이 거부된다.
- 5432 포트를 이미 쓰는 로컬 PostgreSQL이 있으면 먼저 종료한다.

## 평가 결과

같은 평가셋 v2.1(입력 30개, 채점 단위 37문항)로 `phase1-end`(개선 전)와 develop `29a3a22`(개선 후)를 문항마다 3회 실행해 비교했다. 항상 dry-run이다. 측정 조건, 문항별 결과, 재현 명령은 [평가셋과 회귀 평가](docs/evaluation.md)에 있다.

| 지표 | 개선 전 | 개선 후 | 목표 |
|---|---|---|---|
| 추출 정밀도 | 71.1% (101/142) | 80.3% (102/127) | 80% 이상 |
| 중복 판단 정확도 | 83.3% (15/18) | 100.0% (18/18), 94.4% (17/18) | 90% 이상 |
| 기한 정확도 | 17.8% (18/101) | 96.1% (98/102) | 90% 이상 |
| 담당자 오류율 | 3.0% (3/101) | 0.0% (0/102) | 5% 이하 |
| 업무 재현율 | 96.2% (101/105) | 97.1% (102/105) | 90% 이상 유지 |

중복 판단은 11분 간격으로 돌린 두 라운드의 값이다.

## 알려진 한계

- 같은 평가셋으로 프롬프트를 고치고 같은 평가셋으로 다시 쟀다. 전후 수치는 이 평가셋에서의 변화이고, 처음 보는 회의록에서의 일반화는 확인하지 못했다.
- 추출 정밀도 80.3%는 목표를 0.3%p 넘은 경계값이고 측정마다 80.3~86.4%였다. 업무 하나를 여러 티켓으로 쪼개는 초과 25건이 가장 큰 남은 문제다.
- 중복 판단은 6문항 x 3회 = 18회라 표본이 작다. 같은 코드가 시간대에 따라 14/18에서 18/18까지 나왔다.
- 최종 측정은 CLI 경로다. 서버 경로에서는 초안의 제목과 본문이 다른 업무의 것으로 뒤바뀌는 경우가 10회 중 3회 있었다(담당자와 기한은 맞다). 담당자가 카드를 확인하는 단계에서 걸러야 한다.
- 운영 지표(중복 발행률, 오발행률)는 측정하지 않았다. 평가셋은 팀이 만든 가상 회의 요약이다.

## 데이터 출처

외부 데이터 세트는 쓰지 않았다. 평가셋, 샘플 회의록, 시험용 이슈는 모두 팀이 만든 가상 데이터이고 실제 개인정보는 없다. 출처와 이용조건 표는 [데이터 출처와 이용조건](docs/data-sources.md)에 있다. 회의 원문은 LLM 호출 때 OpenAI로 전송되므로 서비스에 올리는 회의록에는 개인정보를 넣지 않는다.

## 보안과 개인정보

- 비밀 값: `.env`는 `.gitignore`로 제외하고 `.env.example`만 올린다. API 키와 토큰은 이미지에 복사하지 않고 실행할 때 환경 변수로 넘긴다.
- 프롬프트 인젝션: 회의 원문, Notion 문서, 기존 이슈를 데이터 태그로 감싸 안의 지시를 따르지 않게 하고, 초안에 원문에 없는 URL이나 명령어가 생기면 경고한다(`agent/guard.py`). 완벽한 방어는 아니다.
- 사람이 확인한 뒤에만 발행: 담당자가 확인하지 않은 항목은 자동으로 발행되지 않고 승인 이력을 남긴다. Compose의 기본값은 `DRY_RUN=true`라 실제 이슈를 만들지 않는다.
- 쓰기 범위 제한: 이슈를 쓸 수 있는 저장소를 `GITHUB_ALLOWED_REPOS`로 제한할 수 있다.
- 호출 한도: LLM 호출 1회 120초, 작업 전체 600초, 회의 원문 40,000자, 중복 판단은 업무당 도구 호출 4회와 30초.
- 운영 설정 보호: demo, local, test 이외의 프로파일에서는 기본 자격증명과 빈 내부 토큰이면 backend가 기동을 거부한다. 이 저장소의 Docker 구성은 로컬과 데모용이고 운영 배포용이 아니다.

## 문서

- `docs/tech-stack.md`: 기술 스택
- `docs/convention.md`: 브랜치 / 커밋 / PR 규칙
- `docs/publish-targets.md`: 발행 대상별 매핑, 상태 대응표, 재발행/결과 불명 정책
- `docs/revisions-and-history.md`: 에이전트 원본/수정본/발행본 리비전, 상태 이력, 승인 이력, 사용자 노출 상태, deprecated 컬럼
- `docs/metrics/README.md`: 수정률, 승인율, 발행 성공률 지표 정의와 SQL
- `docs/dev-env.md`: 포트, CORS, 실행 순서
- `backend/README.md`: API, demo 프로필, 실제 Agent 연결
- `agent/README.md`, `business-mcp/README.md`, `rag-mcp/README.md`: 개발환경과 실행
- `docs/docker-compose.md`: 전체 Docker 실행 안내
- `docs/api/README.md`: API 문서
- `docs/rabbitmq-analysis.md`: RabbitMQ 분석 큐
- `scripts/db/README.md`: 테이블, 시드 데이터, 샘플 회의록
- `docs/evaluation.md`: 평가셋 v2.1, 회귀 평가 방법, 개선 전후 측정 결과, 평가의 한계
- `docs/data-sources.md`: 데이터 출처와 이용조건
- `docs/publish-e2e.md`: 발행 경로 E2E 검증 결과

## 팀 역할과 기여

Phase 1(Capstone)을 이어 Phase 2(Mini-project ③)를 진행한다. Phase 2 기준점은 `phase1-end` 태그다.

| 이름 | Phase 1 역할 | Phase 2 역할 | R 대응 | 맡은 일 | 병합 PR |
|---|---|---|---|---|---|
| 강민규 | PM, Frontend | PM | R1 PM, PO | 문제 정의와 일정, 평가 체계와 측정(#266, #329, #330), 안정성과 보안(#261~#268), 릴리즈 | [병합 PR 목록](https://github.com/MCP-Capstone/meet-to-ticket/pulls?q=is%3Apr+is%3Amerged+author%3Amingyukang5420) |
| 임나윤 | Backend | 풀스택 | R5 Frontend, Demo | 내 티켓 화면(중복 판단 정보, 발행 본문 미리보기, 발행 대상 변경: #243, #245, #247) | [병합 PR 목록](https://github.com/MCP-Capstone/meet-to-ticket/pulls?q=is%3Apr+is%3Amerged+author%3Ajadoolim) |
| 함주헌 | Backend | AI-Pipeline | R4 Backend, Tool | 발행 멈춤과 시간 상한(#299, #302), 발행 E2E 검증(#303), 결과 모름 재시도 수정(#336, #340) | [병합 PR 목록](https://github.com/MCP-Capstone/meet-to-ticket/pulls?q=is%3Apr+is%3Amerged+author%3Ajuhun-ham) |
| 최윤경 | AI | AI-MCP | R3 AI Engineer (RAG, Data) | rag-mcp(#84)와 GitHub MCP(#137), 티켓 출력 형식(#307, #308)과 출력 품질 검증(#309), 프론트 검토와 발행 UX(#322) | [병합 PR 목록](https://github.com/MCP-Capstone/meet-to-ticket/pulls?q=is%3Apr+is%3Amerged+author%3A0unk0) |
| 박종민 | AI | AI-Agent | R2 AI Engineer (흐름) | Agent 흐름 설계와 구현(LangGraph 그래프 #39~#44, Harness #62~#68), 중복 판단 노드와 발행 처리, rag-mcp 연결(#92~#94, #107), 중복 판단 정확도(#324, #337)와 기한 해석(#342) 개선, 발행 직전 재대조 범위 수정(#297) | [병합 PR 목록](https://github.com/MCP-Capstone/meet-to-ticket/pulls?q=is%3Apr+is%3Amerged+author%3Ajongmiiin) |
| 허민규 | 기획, QA, 데이터 | Eval, Data, QA | R6 QA, Eval | 평가 데이터와 QA | [병합 PR 목록](https://github.com/MCP-Capstone/meet-to-ticket/pulls?q=is%3Apr+is%3Amerged+author%3Aminkyu-huh) |
