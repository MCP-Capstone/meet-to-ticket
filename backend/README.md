# backend

Java 21 + Spring Boot 4.1 + Gradle + Spring Data JPA + PostgreSQL

## 실행

1. 루트에서 PostgreSQL을 띄운다. (`docker compose up -d`)
2. 이 폴더에서 실행한다.

```bash
./gradlew bootRun
```

http://localhost:8080/api/health 에서 `{"status":"UP"}`가 나오면 정상이다.

## API 명세 (Swagger)

서버 실행 후 http://localhost:8080/swagger-ui.html 에서 본다. (JSON: `/v3/api-docs`)

- 명세는 코드(컨트롤러, DTO)에서 만들어진다. 따로 문서를 고치지 않는다.
- 로그인이 필요한 API는 Swagger에서 먼저 `POST /api/auth/login`(또는 signup)을 실행하면 이후 요청에 세션 쿠키가 자동으로 붙는다.
- 명세에는 `/api/**`만 나온다. Agent 콜백(`/internal/**`)은 싣지 않는다.

### 작성 규칙

| 위치 | 애너테이션 | 내용 |
|---|---|---|
| 컨트롤러 클래스 | `@Tag(name = "회의")` | 화면에 묶어 보여줄 이름 |
| 메서드 | `@Operation(summary, description)` | 한 줄 요약, 동작 설명 |
| 메서드 | `@ApiResponse(responseCode, description)` | 성공 / 에러 코드별 설명. 에러는 `ErrorResponse` + 예시 |
| DTO 필드 | `@Schema(description, example)` | 필드 설명, 예시 값 |
| 로그인 불필요 API | `@SecurityRequirements` | 자물쇠 표시 제거 (기본은 로그인 필요) |

예시는 `auth/AuthController.java`, `auth/dto/SignupRequest.java` 참고.

아직 구현하지 않은 API는 DTO와 컨트롤러 메서드만 먼저 만들고 `501 Not Implemented`를 돌려주는 껍데기로 둔다. 명세가 먼저 나오고, 구현할 때 메서드 안만 채운다.

## 스크립트

| 명령 | 설명 |
|---|---|
| `./gradlew bootRun` | 서버 실행 (8080) |
| `./gradlew build` | 빌드 + 테스트 |
| `./gradlew test` | 테스트 |

Windows는 `gradlew.bat`을 사용한다.

## 규칙

- 모든 API는 `/api`로 시작한다. 프론트의 Vite 프록시가 `/api`만 백엔드로 전달한다.
- 패키지 기준: `com.mcpcapstone.meettoticket`
- DB 접속 정보는 `application.yml`의 기본값을 쓰고, 다르면 환경 변수(`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`)로 덮어쓴다.
- 테이블은 `ddl-auto: update`로 엔티티 기준 자동 생성된다.

## 로그인 (세션)

JWT 없이 서버 세션으로 로그인 상태를 유지한다. 로그인하면 `JSESSIONID` 쿠키가 발급되고, 세션에 사용자 ID가 저장된다. (유지 시간 2시간)

| Method | URL | 설명 | 응답 |
|---|---|---|---|
| POST | `/api/auth/signup` | 회원가입 후 바로 로그인 | 201 `{ userId, loginId, name }` / 409 `DUPLICATE_LOGIN_ID` |
| POST | `/api/auth/login` | 로그인 | 200 `{ userId, loginId, name }` / 401 `LOGIN_FAILED` |
| POST | `/api/auth/logout` | 로그아웃 | 204 |
| GET | `/api/auth/me` | 내 정보 | 200 / 401 `UNAUTHORIZED` |

- `/api/auth/**`, `/api/health`를 뺀 모든 `/api/**`는 로그인이 필요하다. (`LoginCheckInterceptor`, 없으면 401 `UNAUTHORIZED`)
- Spring이 Agent의 `/internal/**` API를 직접 호출·조회한다. 브라우저 세션과 Vite 프록시를 거치지 않는다.
- 컨트롤러에서 로그인한 사용자 ID 받기

```java
@GetMapping("/api/projects")
public List<ProjectResponse> list(@SessionAttribute(SessionConst.LOGIN_USER_ID) Long userId) { ... }
```

- curl로 테스트할 때는 쿠키를 저장해서 같이 보낸다.

```bash
curl -c cookie.txt -H 'Content-Type: application/json' \
  -d '{"loginId":"demo1","password":"password1","name":"데모"}' localhost:8080/api/auth/signup
curl -b cookie.txt localhost:8080/api/auth/me
```

## 에러 응답

모든 에러는 `{ "code": "...", "message": "..." }` 형식이다. 새 에러는 `common/error/ErrorCode`에 추가하고 `throw new BusinessException(ErrorCode.XXX)`로 던진다.

## 회의 → 분석 → 결과 API

| Method | URL | 설명 |
|---|---|---|
| GET | `/api/projects` | 로그인한 사용자의 프로젝트. 실행 모드 `executionMode`도 반환 |
| GET | `/api/projects/{projectId}/members` | 프로젝트 팀원·GitHub 계정 |
| POST | `/api/projects/{projectId}/meetings` | multipart `file`로 UTF-8 .txt 업로드·저장, 201 |
| GET | `/api/meetings/{meetingId}` | 본문을 제외한 회의 메타데이터 |
| POST | `/api/meetings/{meetingId}/analysis-tasks` | 분석 실행 요청, 202 + taskId |
| GET | `/api/analysis-tasks/{taskId}` | 상태·단계·실행 모드 |
| GET | `/api/analysis-tasks/{taskId}/items` | 종료 후 항목별 검수·발행 결과·집계 |

모든 공개 업무 API는 세션과 프로젝트 소속을 확인한다. 같은 회의에 분석을 다시 요청하면 409다. 같은 파일명으로 별개 회의를 업로드하는 것은 가능하다. 사용자의 본문 수정·승인·티켓 수정 API는 없다.

DB는 `users / projects / project_members / meetings / analysis_tasks / task_items` 6개다. `participants_json / input_json / content_json`은 이번 구현에서 TEXT JSON으로 저장하고 Jackson으로 DTO에 변환한다. 파일 본문은 MEETINGS에 유지한다. 본문 파서는 회의 제목(또는 첫 Markdown 제목), ISO 날짜, 쉼표로 구분한 참여자 이름을 읽는다. `회의 날짜/일시`, `회의 참여자/참석자` 헤더와 Markdown의 `- ` 접두사를 지원한다. UTF-8 BOM은 디코딩 단계에서 한 번 제거하고 나머지 줄바꿈·본문은 보존한다. 참여자 이름 앞뒤 공백은 제거하지만 내부 공백과 영문 대소문자는 보존하며, 등록 이름과 정확히 일치해야 한다. 담당자를 잘못 연결하지 않도록 유사 이름을 자동 매칭하지 않는다. 날짜가 없거나 참여자가 미등록·동명이인이면 400으로 차단한다. 파일 크기 상한은 운영용 설정 `MAX_UPLOAD_SIZE`로 바꿀 수 있으며 기본 10MB다. 확정 제품 제한을 의미하지 않는다.

### Agent 없이 실행 — demo 프로필

먼저 루트 Docker Compose로 PostgreSQL을 실행한다. `backend/`에서:

```bash
./gradlew bootRun --args='--spring.profiles.active=demo'
```

- demo 프로필에서만 초기 사용자 `demo1 / password1`과 프로젝트, 회의 샘플의 팀원 6명을 넣는다. 실제 비밀번호는 `DEMO_PASSWORD`로 변경할 수 있다.
- 팀원 GitHub 계정은 팀 실제 계정(`mingyukang5420, jongmiiin, jadoolim, 0unk0, juhun-ham, minkyu-huh`)이 기본값이다. 순서는 강민규, 박종민, 임나윤, 최윤경, 함주헌, 허민규이며 `DEMO_GITHUB_LOGINS`(쉼표 구분)로 바꿀 수 있다. 빈 값은 NULL로 남긴다.
- 기존 팀원 행은 덮어쓰지 않는다. 예전에 GitHub 계정 없이 시드된 DB라면 새 DB를 쓰거나 `project_members.github_name`을 직접 채운다.
- 업로드용 샘플 회의록: `examples/meetings/*.txt` (데이터 담당 작성, 기대 업무 수는 `scripts/db/README.md`)
- MOCK 실행 상태는 메모리에만 보관하므로 서버 재시작 후 진행 중 작업은 `AGENT_TASK_UNAVAILABLE`로 FAILED 처리된다. 이미 DB에 저장된 종료 결과는 유지된다.
- Agent를 호출하지 않고 **고정 모의 응답**을 반환한다. 실제 LLM 추출·검수·GitHub 발행이 아니다.
- `executionMode=MOCK`이며 모의 승인 항목은 `SKIPPED`, 이슈 번호·URL은 NULL이다. 가짜 GitHub 성공/링크를 만들지 않는다. 화면은 이를 모의 실행으로 표시해야 한다.
- `AGENT_MOCK_SCENARIO=approved`(기본), `empty`, `rejected`, `failed`, `partial`로 저장·상태 시나리오를 확인한다. `approved`는 모의 승인 뒤 실제 발행 생략이며 SUCCEEDED가 아니다.
- 보완·반려 횟수는 Agent 결과로 받는다. Spring이 LLM 검수 루프를 구현하지 않는다.

서버 실행 후 다른 터미널의 `backend/`에서:

```bash
python3 examples/smoke_demo.py
```

이 스크립트는 서버가 MOCK 모드인지 확인한 뒤 로그인 → 프로젝트 선택 → 업로드 → 분석 → 결과 조회를 수행한다. 실행할 때마다 새 시연 회의가 추가된다.

### 실제 Agent 연결

기본값은 LIVE다. demo 프로필 없이 실행하거나, demo 시드(계정 / 프로젝트 / 팀원)를 그대로 쓰면서 Agent만 실제로 붙이려면 demo 프로필에 `AGENT_MODE=live`를 함께 준다. 다음 환경변수를 설정한다.

```dotenv
AGENT_MODE=live
AGENT_BASE_URL=http://localhost:8001
AGENT_INTERNAL_API_KEY=실제_내부_인증키
```

LIVE 모드에서 `AGENT_INTERNAL_API_KEY`가 없거나 공백이면 기동에 실패한다. demo(MOCK)는 키 없이 실행할 수 있다.

Spring은 `POST /internal/v1/analysis-tasks`로 JSON을 전달하고 `GET /internal/v1/analysis-tasks/{taskId}`를 약 2초 간격으로 조회한다. Agent에서 두 API를 제공해야 하며 콜백은 사용하지 않는다. Agent와 MCP 구현은 변경하지 않았다. 내부 키와 GitHub 토큰은 별개이고 요청 스냅샷에 저장하지 않는다.

- `AGENT_POLL_INTERVAL_MS` 기본 2000, HTTP 연결 3초·읽기 5초, 전체 확인 제한 `AGENT_TASK_TIMEOUT` 기본 10분.
- 입력 스냅샷은 `{executionMode, request}` 구조이며 `request`가 실제 전송 JSON이다.
- 보완 최대 2회, 원문 근거·팀원 범위·검수 버전·승인 인자·실제 이슈 링크 등 응답 계약을 확인한 뒤 저장한다.
- 할 일 없음·모두 검수 반려는 COMPLETED. 일부 발행 실패는 PARTIALLY_FAILED, 승인 항목이 전부 실패하면 FAILED다.
- 같은 taskId를 재접수하거나 응답 유실을 이유로 새 이슈 생성을 반복하지 않는다. 생성 여부 불명은 UNKNOWN으로 보존한다.
- 폴링은 중첩 실행 없이 순차 처리한다. 작업 수와 Agent 응답 지연에 따라 개별 조회 간격은 설정값보다 길어질 수 있다.
- 같은 회의의 분석 생성은 회의 행의 DB 잠금 안에서 기존 작업을 확인해 직렬화한다. 외부 Agent 호출 전에 트랜잭션을 끝낸다.
- 종료 결과의 항목과 작업 상태는 한 트랜잭션으로 저장한다. 이미 종료된 작업을 뒤늦은 진행 상태로 되돌리지 않는다.
- Agent가 접수 응답을 주지 않았을 때에도 기존 taskId만 조회하며 재실행하지 않는다. Agent의 프로세스가 재시작돼 작업을 잃었다면 조회 실패로 종료한다.

### 테스트

```bash
./gradlew test
./gradlew bootJar
```

자동 테스트는 격리된 H2 PostgreSQL 호환 모드 DB를 사용해 로컬 업무 DB를 바꾸지 않는다. 파일 파싱, 로그인·접근 차단, 모의 전체 흐름, 빈 결과·반려·부분/전체 실패, HTTP Agent 계약, 잘못된 근거·계정/저장소 결과 차단 등을 확인한다. 실제 Agent·GitHub 연동 성공을 의미하지 않는다.
