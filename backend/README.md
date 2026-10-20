# backend

Java 21 + Spring Boot 4.1 + Gradle + Spring Data JPA + PostgreSQL

## 실행

1. 루트에서 PostgreSQL을 띄운다. (`docker compose up -d`)
2. 이 폴더에서 실행한다.

```bash
./gradlew bootRun
```

http://localhost:8080/api/health 에서 `{"status":"UP"}`가 나오면 정상이다.

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
