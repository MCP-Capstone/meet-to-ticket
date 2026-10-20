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
