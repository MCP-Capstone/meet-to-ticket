# DB 스키마와 개발용 데이터

## 스키마 관리 (Flyway)

스키마는 Flyway 마이그레이션이 만든다. 파일은 `backend/src/main/resources/db/migration/`에 있고, 백엔드가 기동할 때 자동 적용한다. Hibernate는 `ddl-auto: validate`라 엔티티와 DB가 다르면 기동이 실패한다. 테이블을 자동으로 만들거나 고치지 않는다.

| 파일 | 내용 |
|---|---|
| `V1__baseline.sql` | 엔티티 기준 전체 스키마. `ddl-auto: update` 시절의 결과와 같다 |
| `V2__enum_checks_and_dispatch_fk.sql` | enum check 제약을 현재 enum 값으로 재생성 (옛 `EnumCheckConstraintSync` 대체), `analysis_dispatches.task_id` FK 추가 |

### 기동 절차

| DB 상태 | 동작 |
|---|---|
| 빈 DB | V1, V2를 차례로 적용한다 |
| `ddl-auto: update`로 만든 기존 DB (`flyway_schema_history` 없음) | 현재 스키마를 V1로 기록(baseline)하고 V2부터 적용한다. 데이터는 그대로다 |
| Flyway로 관리 중인 DB | 아직 적용하지 않은 마이그레이션만 적용한다 |

설정은 `application.yml`의 `spring.flyway.baseline-on-migrate: true`, `baseline-version: 1`이다.

- 기존 DB를 V1로 간주하므로, 기동 전에 그 DB가 현재 엔티티와 크게 다르지 않아야 한다. 컬럼이 빠졌거나 타입이 다르면 `validate`가 기동 때 실패하므로 즉시 알 수 있다. 길이와 NULL 여부 차이는 검증하지 못한다.
- 테이블이 일부만 있는 DB는 baseline 대상이 아니다. 로컬이면 `docker compose down -v` 후 다시 띄운다.
- V2의 FK 추가는 기존 고아 `analysis_dispatches` 행(대응하는 `analysis_tasks` 없음)을 지운다. 고아 행은 접수 기록만 남은 행이라 재실행을 막는 역할이 없다.

### 마이그레이션 작성 규칙

- 스키마 변경은 새 파일 `V<다음 번호>__<설명>.sql`로만 추가한다. 엔티티만 고치고 마이그레이션을 빼먹으면 `validate`로 기동이 실패한다.
- 이미 머지된 `V*.sql`은 수정하지 않는다. 체크섬이 달라져 모든 환경의 기동이 실패한다.
- 번호가 겹치면(여러 브랜치가 같은 번호를 쓴 경우) 나중에 머지하는 쪽이 번호를 올린다.
- enum 값을 추가하면 해당 컬럼의 check 제약을 새 마이그레이션에서 `DROP CONSTRAINT` 후 `ADD CONSTRAINT`로 갱신한다.
- 기존 행이 있는 테이블에 NOT NULL 컬럼을 넣을 때는 `DEFAULT`로 채우거나, 컬럼 추가, 값 채움, `SET NOT NULL` 순서로 나눈다.
- 기존 데이터가 깨질 수 있는 FK / 제약은 `NOT VALID`로 추가하고, 데이터를 정리한 뒤 `VALIDATE CONSTRAINT`를 실행한다. (V2의 FK가 예시)
- SQL은 PostgreSQL 전용이다. 단위 테스트는 H2(`flyway.enabled: false`, 엔티티로 스키마 생성)로 돌므로 마이그레이션은 아래 검증으로 확인한다.

### 마이그레이션 검증

`MigrationSchemaTest`가 실제 PostgreSQL에서 다음을 확인한다. `MIGRATION_TEST_DB_URL`이 없으면 건너뛴다. CI는 PostgreSQL 서비스 컨테이너로 항상 실행한다.

1. Hibernate가 엔티티로 만든 스키마(`ddl-auto: create`)와 빈 스키마에 마이그레이션을 적용한 결과를 컬럼 / 제약 / 인덱스 정의로 비교해 같은지 확인한다. (이름은 비교하지 않는다. 차이는 V2의 `analysis_dispatches` FK뿐이다)
2. V1과 같은 테이블에 오래된 check 제약과 고아 행을 넣은 "기존 DB"를 baseline으로 기동하면, 빈 DB 결과와 같은 스키마가 되는지 확인한다.

```bash
# 로컬 PostgreSQL이 떠 있을 때 (docker compose up -d postgres). mig_* 스키마를 만들고 지운다.
cd backend
MIGRATION_TEST_DB_URL=jdbc:postgresql://localhost:5432/meettoticket ./gradlew test --tests '*MigrationSchemaTest'
```

### analysis_dispatches 정리 정책

`analysis_dispatches`는 Agent 중복 실행을 막는 접수 기록이자 큐 발행 outbox다. `task_id`는 `analysis_tasks`를 참조한다.

- 작업이 진행 중(`QUEUED`, `RUNNING`)이면 행을 지우지 않는다.
- 작업이 끝난 뒤(`COMPLETED`, `PARTIALLY_FAILED`, `FAILED`) 7일이 지난 행만 지울 수 있다. 기준은 `analysis_tasks.finished_at`이다.
- 프로젝트 삭제는 `ProjectDeletionService`가 해당 프로젝트의 행을 `analysis_tasks`보다 먼저 지운다.
- 7일 경과 행을 지우는 자동 작업은 아직 없다. 필요하면 아래 조건으로 별도 이슈에서 구현한다.

```sql
DELETE FROM analysis_dispatches d
USING analysis_tasks t
WHERE t.analysis_task_id = d.task_id
  AND t.status IN ('COMPLETED', 'PARTIALLY_FAILED', 'FAILED')
  AND t.finished_at < now() - interval '7 days';
```

## 테이블

9개 테이블은 백엔드 JPA 엔티티와 `V1__baseline.sql`이 정의한다.

| 테이블 | 엔티티 |
|---|---|
| users | `user/User` |
| projects | `project/Project` |
| project_members | `project/ProjectMember` |
| meetings | `meeting/Meeting` |
| analysis_tasks | `analysis/AnalysisTask` |
| task_items | `analysis/TaskItem` |
| publish_batches | `analysis/PublishBatch` |
| project_reindexes | `reindex/ProjectReindex` |
| analysis_dispatches | `queue/AnalysisDispatch` |

- `participants_json`은 ProjectMember ID 배열(JSON)이라 DB FK로 보장되지 않는다. 업로드 시 `MeetingService`가 참여자 이름을 같은 프로젝트의 팀원과 정확히 매칭해 저장한다. (미등록 / 동명이인은 400)

## 개발용 데이터 (demo 프로필)

**운영 DB에서 demo 프로필 사용 금지.** 아래 데모 계정과 비밀번호는 공개된 값이다.

```bash
docker compose up -d          # 루트에서
cd backend
./gradlew bootRun --args='--spring.profiles.active=demo'
```

| 데이터 | 내용 |
|---|---|
| 로그인 계정 | `demo1` / `password1` (`DEMO_LOGIN_ID`, `DEMO_PASSWORD`로 변경) |
| 프로젝트 | 기업용 문서 협업 서비스 - 관리자 사용자 목록 CSV 내보내기 (`MCP-Capstone/mcp-capstone`) |
| 팀원 6명 | 아래 표 |
| 회의 / 분석 | 비어 있음. 화면에서 샘플 회의록을 업로드한다 |

| 이름 | GitHub |
|---|---|
| 강민규 | mingyukang5420 |
| 박종민 | jongmiiin |
| 임나윤 | jadoolim |
| 최윤경 | 0unk0 |
| 함주헌 | juhun-ham |
| 허민규 | minkyu-huh |

- 다시 실행해도 이미 있는 계정 / 프로젝트 / 팀원은 추가하지 않고, 기존 행을 덮어쓰지 않는다.
- 이슈 발행은 테스트 저장소 `MCP-Capstone/mcp-capstone`에서만 한다.

## 샘플 회의록

`backend/examples/meetings/*.txt` 한 곳에서만 관리한다. 화면 업로드에 그대로 쓴다.

| 파일 | 회의 날짜 | 기대 신규 업무 |
|---|---|---|
| 10min_1_summary.txt | 2026-10-19 | CSV-01~07, 7건 |
| 30min_1_summary.txt | 2026-10-22 | CSV-08, 1건 |
| 60min_1_summary.txt | 2026-10-26 | CSV-09~10, 2건 |

- 머리말 형식: 첫 줄 `# 제목`, `- 회의 일시: YYYY-MM-DD ...`, `- 회의 참여자: 이름, 이름, ...`
- 참여자 이름은 위 팀원 이름과 정확히 같아야 업로드된다.
- 상대 기한("다음 주" 등)은 회의 날짜 기준으로 해석한다.
