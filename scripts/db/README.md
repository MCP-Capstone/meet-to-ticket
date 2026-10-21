# #25 JPA 엔티티와 개발용 목업 데이터

6개 테이블은 `backend/src/main/java/com/mcpcapstone/meettoticket/entity/`의 JPA 엔티티가 정의한다. 생성 SQL·입력 SQL은 제거했다. `MockDataInitializer`가 EntityManager로 데이터를 넣으며 SQL 초기화는 사용하지 않는다.

## 실행

Java 21과 PostgreSQL이 필요하다. 최신 develop을 기반으로 기존 Gradle·Spring Boot 4.1.1·세션 인증·Swagger 구성을 유지했다. 기존 DB에 적용하지 말고 전용 빈 개발 DB를 만든다.

```bash
createdb mock_meettoticket
cd ~/Desktop/meet-to-ticket/backend
DB_URL=jdbc:postgresql://localhost:5432/mock_meettoticket \
DB_USERNAME=meettoticket DB_PASSWORD=meettoticket \
./gradlew bootRun --args='--spring.profiles.active=mock'
```

DB 접속 사용자·비밀번호는 자신의 PostgreSQL 설정에 맞춘다. 위 meettoticket 값은 예시이며 `demo`는 DB 접속 계정이 아니다.

- `mock` 프로필: Hibernate `ddl-auto=update`로 엔티티 기반 테이블 생성 후 목업 데이터를 하나의 트랜잭션으로 입력한다.
- 프로필 미지정: 최신 develop의 `ddl-auto=update` 설정을 유지하며 엔티티로 테이블을 관리하지만 목업은 입력하지 않는다.
- 같은 초기 데이터로 재실행하면 추가 입력을 생략한다. 대상 4개 테이블의 기존 값이 목업과 다르면 덮어쓰지 않고 시작에 실패한다. 사용 중인 DB에서는 mock 프로필을 해제한다.
- 개발용 자동 DDL 설정이다. 사용자가 요청한 최신 develop 기반 #25 작업 브랜치에서 관리한다.

## 초기 데이터

| 테이블 | 건수 |
|---|---:|
| users | 1 |
| projects | 1 |
| project_members | 6 |
| meetings | 3 |
| analysis_tasks | 0 |
| task_items | 0 |

로그인용 테스트 계정은 **demo / Demo1234!**, 이름은 임나윤이다. BCrypt `$2y$`, cost 10 해시로 저장하며 해당 멤버에 연결한다. 다른 멤버의 서비스 user 연결은 NULL이다. 최신 develop의 기존 `/api/auth/login` API와 BCryptPasswordEncoder로 로그인할 수 있다. 비밀번호나 해시를 API 응답에 포함하지 않는다.

프로젝트: 기업용 문서 협업 서비스 — 관리자 사용자 목록 CSV 내보내기.
GitHub 대상: https://github.com/MCP-Capstone/mcp-capstone

| 이름 | github_name |
|---|---|
| 강민규 | mingyukang5420 |
| 박종민 | jongmiiin |
| 임나윤 | jadoolim |
| 최윤경 | 0unk0 |
| 함주헌 | juhun-ham |
| 허민규 | minkyu-huh |

멤버·회의 PK는 IDENTITY이며 실제 발급된 멤버 PK 6개를 participants_json에 담는다. 회의 등록자는 demo 계정이다. UUID는 분석 작업에서는 Spring, 업무 결과에서는 Agent가 만들어 할당한다. DBML의 JSON은 `@JdbcTypeCode(SqlTypes.JSON)`과 PostgreSQL `json` 컬럼으로 매핑한다. JSON 내 참조의 일치 여부는 초기화 코드가 검증한다. FK는 지연 로딩 ManyToOne으로 연결하며 삭제 cascade는 추가하지 않았다. 상태·action은 확정 스키마의 varchar 길이를 유지하며 이번 흐름에는 CREATE만 사용한다.

## 회의 입력

`backend/src/main/resources/mock/meetings/*.txt`가 초기 데이터의 원본이다. `scripts/db/fixtures/meetings/`에는 같은 내용의 수동 업로드용 사본이 있다.

| 파일 | 날짜 | 기대 신규 업무 |
|---|---|---|
| 10min_1_summary.txt | 2026-10-19 | CSV-01~07, 7건 |
| 30min_1_summary.txt | 2026-10-22 | CSV-08, 1건 |
| 60min_1_summary.txt | 2026-10-26 | CSV-09~10, 2건 |

수정된 입력용 회의 요약 전체를 original_content에 저장한다. 날짜는 시나리오 그대로 보존했으며 상대 기한은 회의 날짜 기준이다. 이미 저장한 회의를 분석하거나 파일을 수동 업로드하는 경로 중 하나를 선택한다. 양쪽을 모두 하면 회의가 추가 등록될 수 있다. 회의 속 ‘테스트 사용자 20명’은 추출 대상 업무의 산출물이며 서비스 users에 20명을 넣는 요구가 아니다. 실제 GitHub API를 호출하거나 토큰을 저장하지 않는다.

## 검증

기본 빌드·테스트:

```bash
./gradlew test
```

PostgreSQL 통합 테스트는 **비어 있는 별도의 테스트 DB**를 지정할 때만 실행한다. 테스트는 create-drop으로 테이블을 만들고 삭제하므로 실제 데이터가 있는 DB를 지정하지 않는다. 기본 실행에서는 통합 테스트가 건너뛰어진다.

```bash
TEST_DB_URL=jdbc:postgresql://localhost:5432/mock_meettoticket_test \
TEST_DB_USERNAME=meettoticket TEST_DB_PASSWORD=meettoticket \
./gradlew test --rerun-tasks
```

## 패키지 구조

```text
com.mcpcapstone.meettoticket
├── entity
│   ├── User.java
│   ├── Project.java
│   ├── ProjectMember.java
│   ├── Meeting.java
│   ├── AnalysisTask.java
│   └── TaskItem.java
├── controller
│   ├── AuthController.java
│   └── HealthController.java
├── service
│   └── AuthService.java
├── dto
│   ├── LoginRequest.java
│   ├── SignupRequest.java
│   └── UserResponse.java
├── repository
│   └── UserRepository.java
├── auth / 세션 상수·로그인 인터셉터·PasswordEncoder 설정
├── mock / MockDataInitializer
└── common / config, error
```

`entity`은 엔티티를 보관하는 계층이며 controller/service/dto/repository와 같은 레벨이다. 기존 인증 API 경로와 동작은 유지한다. API 명세가 없는 도메인에 빈 클래스나 가짜 메서드는 추가하지 않았다. UserRepository와 인증 기능이 동일한 User 엔티티를 사용한다. 최신 develop의 User.createdAt도 유지했으며 DBML에 맞게 login_id/name 100자, password_hash 255자로 컬럼 길이를 맞췄다. 회원가입 API의 입력 길이 제한은 기존 명세를 유지한다.
