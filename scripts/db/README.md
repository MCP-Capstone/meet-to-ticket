# DB 스키마와 개발용 데이터

## 테이블

6개 테이블은 백엔드 JPA 엔티티가 정의한다. 별도 생성 SQL은 두지 않는다. (`ddl-auto: update`)

| 테이블 | 엔티티 |
|---|---|
| users | `user/User` |
| projects | `project/Project` |
| project_members | `project/ProjectMember` |
| meetings | `meeting/Meeting` |
| analysis_tasks | `analysis/AnalysisTask` |
| task_items | `analysis/TaskItem` |

- `participants_json`은 ProjectMember ID 배열(JSON)이라 DB FK로 보장되지 않는다. 업로드 시 `MeetingService`가 참여자 이름을 같은 프로젝트의 팀원과 정확히 매칭해 저장한다. (미등록 / 동명이인은 400)
- 운영 전환 시 `ddl-auto`를 `validate`로 내리고 스키마를 별도로 관리한다. (M3 이후)

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
