# API 명세와 Swagger 사용법

#58 문서화 기준: develop의 Spring 공개 API와 #57 반영 후 Agent `/runs` API.
각 서버가 자신의 코드에서 OpenAPI를 생성한다. 경로·입출력 구현을 바꾸지 않고 설명·예시를 추가했다.

| 제공 서버 | 호출자 | Swagger UI | OpenAPI JSON |
|---|---|---|---|
| Spring, 기본 8080 | 프론트 | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs |
| Agent, 기본 8001 | Spring | http://localhost:8001/docs | http://localhost:8001/openapi.json |

서버 포트를 바꿨다면 문서 주소도 같은 포트로 바꾼다. Spring 문서에 Agent 경로를 가짜 컨트롤러로 등록하지 않는다.
문서용 예시의 ID·계정·이슈 번호는 계약 설명용이며 실제 발행 결과가 아니다.

## Spring 공개 API

| Method | 경로 | 정상 응답 | 역할 |
|---|---|---|---|
| GET | /api/health | 200 | 서버 상태 |
| POST | /api/auth/signup | 201 | 가입·세션 생성 |
| POST | /api/auth/login | 200 | 로그인·세션 생성 |
| POST | /api/auth/logout | 204 | 세션 제거 |
| GET | /api/auth/me | 200 | 내 정보 |
| GET | /api/projects | 200 | 내 프로젝트·실행 모드 |
| POST | /api/projects | 201 | 프로젝트 등록 (등록자는 관리자) |
| GET | /api/projects/{projectId} | 200 | 프로젝트 상세, 멤버, 편집 가능 여부 |
| PUT | /api/projects/{projectId} | 200 | 프로젝트 편집 (관리자만) |
| DELETE | /api/projects/{projectId} | 204 | 프로젝트 삭제 (관리자만, 이름 입력 확인) |
| PUT | /api/projects/{projectId}/members/{memberUserId}/role | 200 | 관리자 지정/해제 (관리자만) |
| GET | /api/users?keyword= | 200 | 멤버로 고를 가입 사용자 검색 |
| GET | /api/projects/{projectId}/members | 200 | 팀원·GitHub 계정 |
| GET | /api/tickets/me | 200 | 내 티켓 (담당자별, `projectId` 필터, 상태별 건수) |
| GET | /api/tickets/{ticketId} | 200 | 티켓 상세 (카드에 보일 값, 담당자만) |
| PATCH | /api/tickets/{ticketId} | 200 | 카드 수정: 제목·본문·담당자·기한 (검토 대기에서만) |
| POST | /api/tickets/{ticketId}/confirm·reject·reopen·restore | 200 | 확인·거절·다시 열기·되돌리기 |
| POST | /api/projects/{projectId}/analysis-tasks | 202 | 회의록 처리 요청 (회의 저장 + 분석 접수, 기획_v16) |
| POST | /api/projects/{projectId}/meetings | 201 | multipart file 업로드·회의 저장 |
| GET | /api/meetings/{meetingId} | 200 | 본문을 제외한 회의 메타데이터 |
| POST | /api/meetings/{meetingId}/analysis-tasks | 202 | 저장된 회의 분석 접수 |
| GET | /api/analysis-tasks/{taskId} | 200 | 상태·오류 조회 |
| GET | /api/analysis-tasks/{taskId}/items | 200 | 종료 항목·집계 조회 |

가입의 `githubName`(GitHub 로그인명)은 선택이다. 미입력이나 공백만 있으면 확인 없이 가입한다.
입력하면 GitHub `GET /users/{login}`으로 사용자 계정인지 확인하고 GitHub 기준 표기로 저장한다. 없는 계정이나 조직 계정은 400 `INVALID_GITHUB_ACCOUNT`이다.
호출 한도(토큰 없이 IP당 시간당 60회)나 장애로 확인하지 못하면 입력값 그대로 저장하고 가입한다.

프로젝트를 등록한 사용자는 관리자(ADMIN)이자 멤버가 되고, 편집(PUT)은 관리자만 한다(아니면 403 `PROJECT_ADMIN_REQUIRED`).
`memberUserIds`는 변경 후 전체 멤버 목록이며, 관리자는 목록에 없어도 남는다. 관리자 지정/해제는 role API(`{"role": "ADMIN" | "MEMBER"}`)로 하고, 마지막 관리자를 해제하면 409 `LAST_PROJECT_ADMIN`이다. 같은 GitHub 저장소는 대소문자가 달라도 409 `DUPLICATE_PROJECT_REPOSITORY`이다.
프로젝트 삭제는 본문 `{"confirmName": "프로젝트 이름"}`이 저장된 이름과 같아야 한다(아니면 400 `PROJECT_NAME_MISMATCH`). 멤버, 회의 기록, 분석 작업과 티켓을 지우고 이미 발행한 GitHub 이슈, Slack 메시지, Notion 페이지는 그대로 둔다. 처리 중인 회의록이 있으면 409 `PROJECT_ANALYSIS_IN_PROGRESS`이다.
저장(POST/PUT)할 때마다 GitHub 계정이 있는 멤버의 저장소 권한을 Agent `POST /projects/check-members`로 확인한다(이전 결과는 먼저 초기화). 멤버별 `repositoryPermission`은 GRANTED/DENIED, 계정이 없으면 NO_ACCOUNT(호출 제외)이다. 멤버 ERROR나 Agent/MCP 전체 실패는 저장을 막지 않고 해당 멤버 `repositoryPermission=null`, `connections.github=ERROR`로 응답한다(전부 성공이면 CONNECTED, 확인할 멤버가 없으면 null). `GET /api/projects/{projectId}`는 외부 호출 없이 저장된 값을 반환한다.
멤버 저장소 권한과 도구 연결은 TASK-006 전까지 확인하지 않는다. GitHub 계정이 없는 멤버만 `NO_ACCOUNT`이고, 확인 전 값은 `null`이다.

로그인·가입·로그아웃·health 외에는 JSESSIONID 세션이 필요하다. Swagger에서 먼저 로그인하면 같은 출처 요청에 쿠키가 적용된다.
Authorize에 쿠키를 직접 넣기보다 login API를 실행한다. 세션 사용자 ID는 입력값으로 노출하지 않는다.
업무 오류는 `{code, message}`. 400(파일/형식/참여자), 401(미로그인), 403(프로젝트 권한),
404(회의/작업 없음), 409(중복 요청/미종료)를 각 API에서 확인한다.
#75부터 분석은 RabbitMQ 큐에 접수하며 Agent 중지 시에도 202를 반환한다. 재시도 후 최종 실패는 상태 조회의 FAILED로 확인한다.

### 담당자별 티켓과 내 티켓 (#166)

Agent 결과를 저장할 때 각 항목의 담당자 이름을 프로젝트 멤버와 연결한다(REQ-010). 멤버 정확히 1명과 연결되면 `PENDING_REVIEW`(검토 대기), 담당자가 없거나 여러 명이거나 멤버와 연결되지 않으면 `ASSIGNEE_REQUIRED`(담당 지정 필요)이다. GitHub 계정이 없는 멤버도 담당자로 연결하며, 이름만 보고 GitHub 로그인명을 추정하지 않는다. 처리 유형은 상태와 별도로 `CREATE`/`LINK`/`UPDATE`이다.
`GET /api/tickets/me`는 로그인 사용자가 담당인 티켓만 반환한다. `projectId`로 거르면 `counts`(`pendingReview`, `confirmed`, `rejected`)도 같은 범위로 센다. 기존 결과 조회 API(`/api/analysis-tasks/{id}/items`)는 바뀌지 않는다.

### 카드 상세·수정과 확인/거절 (#167)

담당자가 카드에서 티켓을 보고, 고치고, 확인하거나 거절한다 (REQ-014). 모든 티켓 API는 그 티켓의 담당자만 쓸 수 있다 (아니면 403 `TICKET_ACCESS_DENIED`, 없는 티켓은 404 `TICKET_NOT_FOUND`). 응답은 모두 바뀐 뒤의 티켓 상세다.

- `GET /api/tickets/{ticketId}`: 제목, 본문(Markdown), 담당자(`memberId`, `name`, `githubName`), 회의록에 적힌 담당자 이름, 기한(`date`, `time`, 원문 `text`), 라벨, 근거, 미결 질문, 카드 경고, 중복 판단 결과, 상태, 처리 유형. 담당자가 고친 값이 있으면 그 값을 보여주고 `edited`가 `true`다. 본문을 HTML로 그릴 때는 sanitize한다.
- `PATCH /api/tickets/{ticketId}`: 보낸 칸만 고친다. 검토 대기에서만 고칠 수 있다 (아니면 409 `TICKET_STATE_CONFLICT`).

  | 필드 | 내용 |
  |---|---|
  | `title` | 공백 불가, 255자 이하 |
  | `body` | 이슈 본문 Markdown, 20000자 이하 |
  | `assigneeMemberId` | 같은 프로젝트 멤버 ID (아니면 400 `INVALID_TICKET_ASSIGNEE`). 바꾸면 그 멤버의 내 티켓으로 옮겨 간다 |
  | `due` | `{"date": "2026-10-25", "time": "18:00"}`. `date`를 `null`로 보내면 기한 없음. 시각만 보내면 400 |

- 상태 전이 (기획 4.2). 표에 없는 전이는 409 `TICKET_STATE_CONFLICT`다.

  | 요청 | 전이 |
  |---|---|
  | `POST .../confirm` | 검토 대기 → 확인 |
  | `POST .../reject` | 검토 대기 → 거절 |
  | `POST .../reopen` | 확인 → 검토 대기 |
  | `POST .../restore` | 거절 → 검토 대기 |

일괄 발행(TASK-034)은 카드에 보이는 값(고친 값이 있으면 고친 값)으로 확인(`CONFIRMED`)된 티켓만 처리 유형대로 발행한다.

### 회의록 처리 요청 (#153)

`POST /api/projects/{projectId}/analysis-tasks`는 회의 저장과 분석 접수를 한 번에 한다 (기획_v16 REQ-006). 업로드 화면은 이 API를 쓴다. 기존 업로드(`POST /api/projects/{projectId}/meetings`)와 분석 시작(`POST /api/meetings/{meetingId}/analysis-tasks`)은 프론트가 옮길 때까지 유지한다.

요청은 `multipart/form-data`다.

| 필드 | 필수 | 내용 |
|---|---|---|
| `meetingDate` | O | 회의 날짜 `YYYY-MM-DD`. 상대 기한 계산 기준 |
| `content` | 둘 중 하나 | 붙여넣은 회의록 텍스트 |
| `file` | 둘 중 하나 | 회의록 파일. UTF-8 `.md` 또는 `.txt` |
| `title` | | 회의 제목. 비우면 회의록 첫 `#`·`##` 제목줄, 파일 이름, `<회의 날짜> 회의` 순 |

- 참석자 줄과 멤버 GitHub 계정은 요구하지 않는다. 담당자는 추출 단계에서 멤버와 연결하고, 계정이 없는 멤버는 이슈에 이름만 적는다 (REQ-010).
- 응답은 기존 분석 시작과 같은 `{taskId, meetingId, status: "QUEUED", executionMode}`다. 이후 `GET /api/analysis-tasks/{taskId}`로 조회한다.
- 날짜 누락·형식 오류, 회의록 없음, `content`와 `file` 동시 입력, 확장자·인코딩 오류는 400 `INVALID_MEETING_INPUT`이다. 이때 회의는 저장되지 않는다.

```bash
# 붙여넣기
curl -b cookie.txt -F meetingDate=2026-10-22 -F 'content=# CSV 점검
박서연이 내일까지 API를 수정한다.' http://localhost:8080/api/projects/1/analysis-tasks
# md 파일
curl -b cookie.txt -F meetingDate=2026-10-22 -F file=@회의록.md http://localhost:8080/api/projects/1/analysis-tasks
```

### 외부 AI·GitHub 없이 테스트

1. 루트에서 `docker compose up -d postgres rabbitmq`로 DB와 큐를 준비하고 backend에서 `./gradlew bootRun --args='--spring.profiles.active=demo'` 실행.
2. Swagger 접속 후 `POST /api/auth/login`에 demo 계정 입력. 기본 demo1 / password1은 개발용이며 환경변수로 바뀔 수 있다.
3. `GET /api/projects`로 프로젝트 ID, `GET /api/projects/{projectId}/members`로 실제 팀원 확인.
4. TXT 파일에 제목·날짜·참여자를 준비하고 업로드 실행. 예시:

   ```text
   # CSV 중간 점검
   회의 날짜: 2026-10-22
   참여자: 박종민, 허민규
   박종민이 내일 오후 5시까지 CSV 빈 결과 오류를 수정한다.
   ```

   참여자는 선택한 프로젝트 등록 이름과 정확히 일치해야 한다. 원본의 여러 줄 참석자·역할 설명은 현재 미지원.
5. 반환된 meetingId로 분석 POST 실행. 본문은 없으며 202의 taskId를 복사.
6. 상태 GET을 반복하고 COMPLETED / PARTIALLY_FAILED / FAILED에서 중단.
7. items GET으로 항목·집계 확인. 처리 중 items GET은 409.

기본 approved 시나리오는 고정 항목 1건의 SKIPPED이며 실제 생성 성공은 0이다.
서버를 재시작할 때 `AGENT_MOCK_SCENARIO=empty|rejected|failed|partial` 중 하나로 결과 분기를 확인할 수 있다.
MOCK 메모리 작업은 재시작하면 사라져 진행 중 작업이 실패할 수 있다. DB 종료 결과는 유지된다.

## Agent 내부 API — 현재 구현

| Method | 경로 | 응답 |
|---|---|---|
| POST | /runs | 기본 202 `{taskId, status: ACCEPTED}` |
| POST | /runs?wait=true | 로컬 테스트용 200 최종 결과 |
| GET | /runs/{task_id} | 200 진행·종료 결과, 알 수 없는 작업은 404 |
| GET | /runs/{task_id}/history | 200 노드별 실행 기록(checkpoint, 시간 순서), 재시작하면 사라짐, 알 수 없는 작업은 404 |
| GET | /health | 200 설정·상태, 외부 연결 성공 검증은 아님 |

Agent 환경에서 `uvicorn main:app --port 8001`로 실행한다. 설치는 [Agent 안내](../../agent/README.md)를 따른다.
문서 조회만으로 LLM·MCP를 호출하지 않는다. POST는 실제 업무를 실행하므로 단독 테스트는
`agent/.env`의 `DRY_RUN=true`, `BACKEND_BASE_URL=` 설정으로 발행·콜백을 끈다. 설정 로더는 .env를 환경변수보다 우선한다.
DRY_RUN도 실제 추출·검수 LLM 호출과 모델 API 키가 필요하다. 키는 Swagger 요청 JSON에 넣지 않는다.
현재 Agent dry-run은 승인 항목을 SKIPPED(발행 생략)로 반환한다. 이를 실제 GitHub 생성 거절로 해석하지 않는다.
가짜 LLM·MCP로 확인하려면 Agent의 자동 테스트를 실행한다.

현재 Agent API는 내부 인증을 강제하지 않으며 `X-Internal-Api-Key` 보안 스키마를 가짜로 표시하지 않는다.
Spring의 `AGENT_INTERNAL_API_KEY`는 선택 설정이며 지정하면 헤더로 전송한다. Agent의 실제 키 검증은 아직 없다.
Agent HTTP 오류는 `{detail}`이고 요청 검증은 422다. 실행 실패는 HTTP 200의 FAILED일 수 있다.
RUNNING만 중복 409로 막으며 종료된 taskId POST는 재실행될 수 있으므로 Spring처럼 접수 후 GET만 사용한다.
실행 상태는 메모리 저장이며 재시작·멀티워커 간 공유를 보장하지 않는다.

## 이전 MD 설계와 현재 구현의 대응

기존 `Spring_Agent_내부_API_명세서.md` v0.2는 설계 기준이다. 최신 develop은 #57의 변환 계층을 사용한다.

| 항목 | 이전 MD v0.2 | 현재 Agent → Spring |
|---|---|---|
| 접수 | POST /internal/v1/analysis-tasks | POST /runs → Spring QUEUED |
| 조회 | GET /internal/v1/analysis-tasks/{taskId} | GET /runs/{task_id} |
| 종료 상태 | COMPLETED/PARTIALLY_FAILED/FAILED | SUCCEEDED/FAILED → 항목 결과로 Spring 상태 정규화 |
| 진행 단계 | 단계별 응답 | 보통 RUNNING만 반환. Spring은 기본 EXTRACTING으로 표시 |
| 항목 ID | itemId | taskItemId → itemId |
| 집계 | succeeded 등 | published, linked, needsCheck 등. Spring은 항목에서 summary 재계산 |
| 검수 | APPROVED/REJECTED | PENDING/NEEDS_CHECK도 가능. 승인 외는 Spring REJECTED/BLOCKED |
| 오류 | {error:{code,message,taskId}}, 400/401 | {detail}, 422/404/409. 내부 인증 검증은 현재 없음 |
| 중복 ID | 종료 여부 무관 409 | RUNNING인 ID만 409 |
| 콜백 | 사용 안 함 | Agent가 `BACKEND_BASE_URL`을 설정하면 `/internal/tasks/{id}/progress`로 진행 단계를 보고하고 Spring이 단계를 갱신한다. `/result`는 202만 반환하며 결과는 폴링으로 받는다 |

이전 내부 경로·키 검증·MD의 모든 검수 정책이 구현됐다고 의미하지 않는다. 차이를 해소하는 기능 수정은 별도 작업이다.

## 검증과 명세 내려받기

```bash
# backend/
./gradlew test bootJar

# agent/ (가상환경 활성화 후)
python -m pytest -q

# 서버 실행 후 선택적으로 JSON 저장
curl --fail http://localhost:8080/v3/api-docs -o /tmp/spring-openapi.json
curl --fail http://localhost:8001/openapi.json -o /tmp/agent-openapi.json
```

Spring 테스트는 생성된 스키마·인증·업로드·상태 코드·예시를 검증한다.
Agent 테스트는 실제 경로·스키마·200/202/404/409/422와 가짜 의존성 실행을 확인한다. 실제 GitHub에 생성하지 않는다.
문서가 코드에서 생성되므로 내려받은 JSON을 수동 편집해 별도 원본으로 유지하지 않는다.
