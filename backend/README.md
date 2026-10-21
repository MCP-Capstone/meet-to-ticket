# backend

Java 21 + Spring Boot 4.1 + Gradle + Spring Data JPA + PostgreSQL

## 실행

1. 루트에서 PostgreSQL을 띄운다. (`docker compose up -d`)
2. 아래 명령은 스키마가 이미 준비된 DB에서 실행한다. 처음 만든 빈 개발 DB에서는 [목업 사용 안내](../scripts/db/README.md)에 따라 `mock` 프로필로 먼저 초기화한다.

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

예시는 `controller/AuthController.java`, `dto/SignupRequest.java` 참고.

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
- 기본 `ddl-auto: validate`는 기존 스키마만 검증한다. 전용 개발 DB에서는 `mock` 프로필의 `update`로 테이블을 생성/갱신하고 목업 데이터를 넣는다.

## 로그인 (세션)

JWT 없이 서버 세션으로 로그인 상태를 유지한다. 로그인하면 `JSESSIONID` 쿠키가 발급되고, 세션에 사용자 ID가 저장된다. (유지 시간 2시간)

| Method | URL | 설명 | 응답 |
|---|---|---|---|
| POST | `/api/auth/signup` | 회원가입 후 바로 로그인 | 201 `{ userId, loginId, name }` / 409 `DUPLICATE_LOGIN_ID` |
| POST | `/api/auth/login` | 로그인 | 200 `{ userId, loginId, name }` / 401 `LOGIN_FAILED` |
| POST | `/api/auth/logout` | 로그아웃 | 204 |
| GET | `/api/auth/me` | 내 정보 | 200 / 401 `UNAUTHORIZED` |

- `/api/auth/**`, `/api/health`를 뺀 모든 `/api/**`는 로그인이 필요하다. (`LoginCheckInterceptor`, 없으면 401 `UNAUTHORIZED`)
- Agent 콜백은 로그인 대상이 아니도록 `/internal/**`에 둔다. (Vite 프록시도 거치지 않는다)
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

## #25 JPA 엔티티와 목업 데이터

엔티티는 `entity/`에 두고 `controller/`, `service/`, `dto/`, `repository/`를 같은 레벨에 둔다. 인증 컨트롤러·서비스·DTO와 UserRepository도 각각 해당 계층에 배치했다. 세션 상수는 `common/constant`, 로그인 인터셉터는 `common/interceptor`, 비밀번호 설정은 `common/config`에 둔다.

개발용 데이터 생성은 [목업 사용 안내](../scripts/db/README.md)를 따른다. **운영 DB에서 mock 프로필 사용 금지.** 목업은 전용 개발 DB의 mock 프로필에서만 입력되며 기존 로그인 계정과 같은 User 엔티티·PasswordEncoder를 사용한다.
