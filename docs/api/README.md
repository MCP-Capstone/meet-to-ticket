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
| GET | /api/projects/{projectId}/members | 200 | 팀원·GitHub 계정 |
| POST | /api/projects/{projectId}/meetings | 201 | multipart file 업로드·회의 저장 |
| GET | /api/meetings/{meetingId} | 200 | 본문을 제외한 회의 메타데이터 |
| POST | /api/meetings/{meetingId}/analysis-tasks | 202 | 저장된 회의 분석 접수 |
| GET | /api/analysis-tasks/{taskId} | 200 | 상태·오류 조회 |
| GET | /api/analysis-tasks/{taskId}/items | 200 | 종료 항목·집계 조회 |

로그인·가입·로그아웃·health 외에는 JSESSIONID 세션이 필요하다. Swagger에서 먼저 로그인하면 같은 출처 요청에 쿠키가 적용된다.
Authorize에 쿠키를 직접 넣기보다 login API를 실행한다. 세션 사용자 ID는 입력값으로 노출하지 않는다.
업무 오류는 `{code, message}`. 400(파일/형식/참여자/계정), 401(미로그인), 403(프로젝트 권한),
404(회의/작업 없음), 409(중복 요청/미종료)를 각 API에서 확인한다.
#75부터 분석은 RabbitMQ 큐에 접수하며 Agent 중지 시에도 202를 반환한다. 재시도 후 최종 실패는 상태 조회의 FAILED로 확인한다.

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
