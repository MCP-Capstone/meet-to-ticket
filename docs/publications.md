# 발행 기록과 멱등키

관련 이슈: #263 (PR1: 테이블과 상태 기록, PR2: 크래시 복구 워커). 기존 흐름은 [발행 대상별 매핑과 정책](publish-targets.md), [발행 실패/재시도 복구](publish-recovery.md)를 먼저 읽는다.

## 왜 필요한가

외부 발행(GitHub, Notion, Slack)의 중복 방지는 Agent가 외부 시스템을 조회(GitHub 댓글 표식, 같은 `work_key`의 열린 이슈 검색)하는 방식에만 기댔다. 조회와 쓰기 사이에 틈이 있어 경쟁 조건이 남고, Notion/Slack은 조회 수단 자체가 없다. 이 PR은 Backend DB에서 "같은 논리적 발행은 한 번만 외부로 보낸다"를 먼저 보장한다. 외부 조회는 그대로 두고 이중으로 막는다.

## 테이블 (Flyway V5, V6)

`publications`: 티켓 하나가 대상 하나에 하는 외부 쓰기 한 건. 티켓을 지우면(프로젝트 삭제) `ON DELETE CASCADE`로 함께 지운다.

| 컬럼 | 설명 |
|---|---|
| publication_id | PK (identity) |
| task_item_id | 티켓. `task_items` FK, ON DELETE CASCADE |
| target | `GITHUB`, `SLACK`, `NOTION` (CHECK) |
| operation | `CREATE`, `LINK`, `UPDATE`, `COMMENT` (CHECK) |
| idempotency_key | 아래 "키 구성". `unique(target, idempotency_key)` |
| status | `RESERVED`, `SENT`, `CONFIRMED`, `UNCERTAIN`, `FAILED` (CHECK) |
| attempt_count | 예약한 횟수. 처음 1, 같은 키로 새 시도를 시작할 때마다 +1 |
| publish_batch_id | 마지막 시도를 보낸 발행 묶음. FK는 없다 |
| external_id, external_url | 확정된 외부 항목. GitHub 이슈 번호 / Notion 페이지 ID, 링크. Slack은 id가 없다 |
| error_code | 실패/결과 모름의 원인 코드 |
| outcome_unknown | V6. 이 키가 한 번이라도 UNCERTAIN이 됐고 아직 확인되지 않았는지. 재예약해도 유지된다 ("결과 모름 보존" 참고) |
| created_at, reserved_at, sent_at, updated_at | 첫 예약, 마지막 예약, 마지막 접수, 마지막 상태 변경 시각 |

`updated_at`은 복구 워커가 멈춘 예약을 찾는 기준이고, `sent_at`은 접수 표시 전에 멈췄는지 가리는 데 쓴다. 인덱스는 `task_item_id`, `publish_batch_id`, `(status, updated_at)`에 둔다.

## 키 구성

```
v1:{ticketId}:{target}:{operation}        예) v1:7c9e...:GITHUB:CREATE
```

| 요소 | 이유 |
|---|---|
| `v1` | 키 형식 버전. 구성을 바꾸면 올려서 옛 키와 섞이지 않게 한다 |
| ticketId | 같은 티켓이 같은 대상에 외부 항목을 두 번 만들지 않는다 |
| target | 대상이 다르면 별개의 발행이다 (실패 후 대상을 바꾸면 새 키) |
| operation | `CREATE`/`LINK`/`UPDATE`는 Agent에 보내는 처리 유형이다. 알림 댓글만 다시 보내는 작업은 `COMMENT`로 분리한다. 본문 갱신이 확정된 뒤 댓글만 다시 보내야 하므로 UPDATE와 같은 키를 쓸 수 없다 |

이슈 본문은 `ticketId + target + published revision id`를 제안했지만 이렇게 정했다.

- PUBLISHED 리비전은 발행이 성공한 뒤에야 생기고 티켓당 1건이다([리비전과 상태 이력](revisions-and-history.md)). 예약 시점에는 알 수 없는 값이다.
- 수정으로 새 USER_EDIT 리비전이 생기는 것은 실패/되돌리기 뒤뿐인데, 이때 키를 바꾸면 이전 시도가 결과 모름(UNCERTAIN)인 채로 새 키가 열려 중복을 막지 못한다. 같은 키를 쓰면 실패한 행을 다시 예약하므로 수정 후 재발행도 막히지 않는다.
- 티켓은 대상마다 하나의 외부 항목만 만든다. 확정된 키가 한 번 더 쓰이면 곧 중복이다.

알려진 한계: 처리 유형을 바꿔(예: CREATE -> LINK) 다시 발행하면 다른 키라서 이전 키의 UNCERTAIN 행이 남는다. 이 행은 자동으로 정리하지 않는다. 같은 키가 아니라서 새 키의 발행을 막지도 않는다.

## 상태 전이

| 현재 | 갈 수 있는 상태 | 언제 |
|---|---|---|
| (없음) | RESERVED | 처음 예약. `PublishService.prepare`가 티켓을 발행 중으로 바꾸는 같은 트랜잭션에서 INSERT |
| RESERVED | SENT | Agent가 묶음을 접수함(접수 확인에 실패해 접수 여부를 모르는 경우 포함) |
| RESERVED | CONFIRMED, UNCERTAIN, FAILED | SENT 표시보다 Agent 보고가 먼저 도착한 경우, 또는 접수 취소(FAILED) |
| SENT | CONFIRMED | 외부 반영이 확정됨 |
| SENT | UNCERTAIN | 결과 모름, 보고에서 빠짐, 기한 초과(`RESULT_TIMEOUT`) |
| SENT | FAILED | 반영되지 않은 것이 확실한 실패 |
| FAILED | RESERVED | 같은 키로 새 시도(조건부 UPDATE). `attempt_count` +1 |
| UNCERTAIN | RESERVED | 재대조할 수 있는 대상(`PublishTarget.recheckable`, GitHub)만. Agent가 발행 직전 재대조한다 |
| UNCERTAIN | CONFIRMED, FAILED | 담당자의 `resolve-outcome` 확인(반영됨 / 반영되지 않음). 반영되지 않음은 `VERIFIED_NOT_APPLIED`로 FAILED |
| CONFIRMED | (없음) | 종결 상태. 같은 키로는 다시 보내지 않는다 |

표에 없는 전이는 모두 불허다(`PublicationStatus.next()`, 모든 조합을 `PublicationStatusTest`가 검증). 모든 전이는 "현재 상태가 이 중 하나일 때만" 거는 조건부 UPDATE 한 문장이라서 영향받은 행이 0이면 다른 요청이 먼저 바꾼 것이다(낙관적 전이).

### 예약 결과

`PublicationLedger.reserve`는 RESERVED를 받은 쪽만 Agent에 보낼 수 있게 한다.

| 기존 행 | 결과 | PublishService 동작 |
|---|---|---|
| 없음 / FAILED / UNCERTAIN(GitHub) | RESERVED | 계속 진행 |
| UNCERTAIN(GitHub CREATE), 결과 모름이 된 뒤 `PUBLISH_GITHUB_RECHECK_DELAY`(기본 3분) 전 | TOO_SOON | 429 `PUBLISH_RETRY_TOO_SOON`, 요청 전체 롤백 (아래 "GitHub 신규 이슈 재시도 대기") |
| RESERVED, SENT | IN_PROGRESS | 409 `TICKET_STATUS_CONFLICT`, 요청 전체 롤백 |
| CONFIRMED | ALREADY_CONFIRMED | 409 `TICKET_STATUS_CONFLICT` |
| UNCERTAIN(Notion, Slack) | VERIFICATION_REQUIRED | 409 `TICKET_STATUS_CONFLICT` (`resolve-outcome`을 먼저 남겨야 한다) |

티켓 상태 검사(`FAILED`만 재시도, `MANUAL_CHECK`는 409)가 먼저 막으므로 정상 흐름에서 409는 거의 나오지 않는다. 발행 기록은 티켓 상태 검사가 우회됐을 때(데이터 불일치, 동시 요청)를 DB에서 막는 두 번째 방어선이다.

### UNCERTAIN 정책

"결과 모름은 확인 전에는 다시 보내지 않는다"는 기존 정책(#280, #285, #291)과 같다.

- Notion/Slack: 재대조할 수단이 없어 `resolve-outcome` 전에는 UNCERTAIN -> RESERVED가 막힌다. 반영됨이면 CONFIRMED(링크 저장), 반영되지 않음이면 FAILED로 끝나 다시 시도할 수 있다.
- GitHub: 담당자가 확인했다는 체크 뒤 다시 시도하면 Agent가 이슈, 댓글, 섹션을 재대조한다(`previousOutcomeUnknown`). `resolve-outcome`은 GitHub에는 열려 있지 않다(409). 그래서 GitHub의 UNCERTAIN -> RESERVED는 허용하되 Agent 재대조가 확인 역할을 한다. `PUBLISH_RETRY_NOT_ALLOWED`(`retryable=false`) 같은 기존 티켓 단위 차단은 그대로 먼저 적용된다.

### GitHub 신규 이슈 재시도 대기 (#336)

GitHub CREATE의 재대조는 이전 시도가 만든 이슈를 GitHub 검색 API로 찾는다. 검색 색인은 새 이슈를 몇 초에서 1분 넘게 늦게 반영해, 결과 모름 직후 다시 보내면 이미 만든 이슈를 못 찾고 하나 더 만든다(#303 E2E에서 재현, `docs/publish-e2e.md`). 그래서 두 겹으로 막는다.

- Backend: CREATE 기록이 UNCERTAIN이면 결과 모름이 된 시각(UNCERTAIN 행의 `updated_at`) 뒤 `PUBLISH_GITHUB_RECHECK_DELAY`(기본 3분)가 지나야 다시 예약한다. 접수 시각(`sent_at`)으로 세지 않는 이유는 재대조나 앞 티켓 때문에 쓰기가 접수 뒤 몇 분 늦게 나갈 수 있어서다 (#340). 그 전에는 429 `PUBLISH_RETRY_TOO_SOON`이다. 기다리면 풀리는 거절이라 화면이 상태 충돌(409)처럼 목록을 막지 않고 메시지만 보여 준다. 재시도와 수정 후 재발행 모두 예약을 거치므로 같이 적용된다.
- Agent: 검색에서 못 찾으면 최근 열린 이슈 30개를 목록 API로 보고, 제목이 같은 이슈와 최근 10개의 본문 끝줄(회의 ID, work_key)을 확인한다. 목록에는 다른 회의의 같은 제목 이슈가 섞일 수 있어 제목만으로는 판정하지 않는다 (#340). 목록 API도 몇 초 늦을 수 있어 Backend 대기와 함께 쓴다.

LINK, UPDATE는 이슈 번호로 댓글과 본문을 직접 조회해 색인 지연이 없으므로 기다리지 않는다. 확정 실패(FAILED)도 이전 이슈가 없으므로 기다리지 않는다.

## 발행 흐름과의 연결

| 시점 | 위치 | 발행 기록 |
|---|---|---|
| 발행 준비 | `PublishService.prepare` | 티켓마다 `reserve`. 티켓 상태 변경과 같은 트랜잭션 |
| Agent 접수 | `PublishService.dispatch` | `markSent`(별도 트랜잭션, 실패해도 발행은 되돌리지 않음) |
| Agent 접수 거절(미전송 확실) | `PublishService.abort` | 묶음의 진행 중 행을 FAILED(`PUBLISH_NOT_ACCEPTED`) |
| 결과 반영 | `PublishService.applyResult` | 티켓에 결과를 쓴 직후 `settle` |
| 결과 모름 확인 | `TicketService.resolveOutcome` | `resolveUncertain` |

`settle`이 티켓 결과를 발행 기록 상태로 옮기는 규칙:

| 티켓 결과 | 발행 기록 |
|---|---|
| PUBLISHED, LINKED, UPDATED (UPDATED + COMMENT_FAILED 포함) | CONFIRMED. 본문 갱신은 확정이고 댓글은 COMMENT 작업이 따로 맡는다 |
| SKIPPED (발행 생략) | CONFIRMED로 기록하지 않는다. 외부에 쓰지 않았으므로 예약 행을 지운다 |
| FAILED + 결과 모름, `NOTION_VERIFY_REQUIRED`, `SLACK_VERIFY_REQUIRED`, `MARKER_CHECK_INCOMPLETE` | UNCERTAIN |
| 그 밖의 FAILED | FAILED (`error_code` 저장) |
| 보고에서 빠진 티켓, 기한 초과 | UNCERTAIN (티켓은 결과 모름 실패로 저장) |
| 알림 댓글 재전송 성공 | COMMENT CONFIRMED |
| 알림 댓글 재전송 실패(`COMMENT_FAILED`) | COMMENT FAILED. 다시 보낼 수 있다 |
| 알림 댓글 표식 확인 불완전, 결과 모름 | COMMENT UNCERTAIN |

## 결과 모름 보존 (V6)

UNCERTAIN 기록이 재예약(reopen)된 뒤 확정 실패, 접수 취소, 발행 생략으로 끝나면, 이전에 외부에 반영됐는지는 여전히 모르는데 기록이 FAILED가 되어 "안전하게 다시 보내도 된다"로 읽힐 수 있었다. 두 가지로 막는다.

- `publications.outcome_unknown`: UNCERTAIN이 되면 true, 재예약해도 유지, CONFIRMED 또는 담당자의 "반영되지 않음" 확인(`resolve-outcome`)에서만 false. 이 표시가 있으면 `settle`은 FAILED 대신 UNCERTAIN을 쓰고, 발행 생략은 예약을 지우지 않고 UNCERTAIN으로 두며, 접수 취소(`releaseBatch`)도 FAILED 대신 UNCERTAIN으로 되돌린다. V6 이전에 이미 UNCERTAIN인 행은 true로 채운다.
- 본문 발행의 결과 판정(`PublicationLedger.outcome`)이 티켓의 `mustRecheckOnPublish`(지금 결과 모름 또는 지난 결과 모름 `previousOutcomeUnknown`)도 본다. 표시가 없는 V6 이전 행에도 티켓 쪽 정보로 같은 결론이 난다.

FAILED 상태의 행에는 이 표시가 남지 않는다(담당자가 반영되지 않음을 확인하면 FAILED로 바꾸면서 표시를 푼다). 그래서 복구 워커가 FAILED를 안전하다고 읽어도 된다.

## 복구 워커

`PublicationRecoveryWorker`(스케줄러)가 `PublicationRecovery.recoverStale`을 주기적으로 호출한다.

### 대상

다음을 모두 만족하는 행만 정리한다.

1. 상태가 RESERVED 또는 SENT이고 `updated_at`이 `stale-after`보다 오래됐다.
2. 티켓이 그 행의 묶음을 기다리는 중이 아니다. 즉 `티켓 상태 = PUBLISHING`이고 `티켓.publish_batch_id = 행.publish_batch_id`인 경우가 아니다.

2번이 기존 만료 처리와의 경계다. 티켓이 아직 기다리는 행은 묶음과 함께 기존 경로가 끝낸다. 끝난 묶음에 티켓만 발행 중으로 남는 비정상(티켓 쪽 불일치)은 복구 워커가 티켓을 건드리지 않고 경고 로그만 남긴다.

### 판단과 전이

행마다 별도 트랜잭션에서 티켓 행을 잠근 뒤 현재 상태를 다시 읽어 판단한다.

| 티켓의 상태 | 발행 기록 | 이유 |
|---|---|---|
| 이 묶음의 결과를 가짐 (PUBLISHED, LINKED, UPDATED, SKIPPED, FAILED) | `settle`과 같은 규칙으로 CONFIRMED / FAILED / UNCERTAIN / 행 삭제 | 티켓에는 결과가 반영됐는데 기록만 진행 중으로 남은 경우. 결과 모름 보존 규칙이 그대로 적용된다 |
| 그 밖 + RESERVED + 접수 표시 없음 + 이력에 접수 취소(`PUBLISH_NOT_ACCEPTED`)가 이 예약 이후로 있음 + 결과 모름 표시 없음 | FAILED (`PUBLISH_NOT_ACCEPTED`) | 보내기 전이 확실한 유일한 경우. 재시도 가능 |
| 그 밖 모두 (SENT, 접수 표시 전에 멈춘 RESERVED 포함) | UNCERTAIN (`PUBLISH_RECOVERY_UNCERTAIN`) | 쓰기가 시작됐는지 알 수 없다. 자동으로 다시 보내지 않는다 |

RESERVED는 Agent 호출 직후 죽어도 남기 때문에 접수 취소 증거가 없으면 SENT와 똑같이 결과 모름이다. 외부 마커를 조회해 CONFIRMED로 올리는 일은 하지 않는다(Backend는 외부 조회 수단이 없고 Agent 계약을 바꾸지 않는다). GitHub는 이후 Agent의 발행 직전 재대조가 그 역할을 한다.

UNCERTAIN으로 보낼 때 티켓도 같이 맞춘다. 맞추지 않으면 다시 발행할 때 Agent가 재대조하지 않거나 Notion/Slack이 수동 확인 없이 중복 발행될 수 있다.

| 티켓 상태 | 처리 |
|---|---|
| CONFIRMED, FAILED | FAILED + `publishOutcomeUnknown`(재시도 가능). 상태 이력 reason은 `PUBLISH_RECOVERY_UNCERTAIN`. 이미 결과 모름인 FAILED는 그대로 둔다 |
| PENDING_REVIEW, ASSIGNEE_REQUIRED | 상태는 두고 `previousOutcomeUnknown`만 남겨 다음 발행에서 재대조(발행 대상도 바꿀 수 없다) |
| 그 밖 (PUBLISHED, LINKED, UPDATED, SKIPPED, 다른 묶음이 기다리는 PUBLISHING) | 건드리지 않는다 |

### 이후 경로

| 대상 | UNCERTAIN 이후 |
|---|---|
| GitHub | 티켓 재시도 -> UNCERTAIN -> RESERVED(재예약) -> Agent가 이슈/댓글/섹션 재대조 |
| Notion, Slack | 티켓이 MANUAL_CHECK가 되어 `resolve-outcome` 전에는 재시도와 재예약 모두 409. 반영됨이면 CONFIRMED, 반영되지 않음이면 FAILED로 끝나 다시 시도 |

### 멱등과 동시성

- 전이는 `UPDATE ... WHERE id = ? AND attempt_count = ? AND status IN (RESERVED, SENT)` 한 문장이다. 읽은 뒤 다른 요청이 행을 끝내거나 다시 예약하면(시도 횟수가 바뀐다) 0행이라 아무것도 바꾸지 않는다.
- 여러 인스턴스가 동시에 돌려도 티켓 행 잠금으로 줄을 서고, 늦은 쪽은 이미 진행 중이 아닌 행을 보고 건너뛴다. 티켓 이력도 한 번만 남는다. 다시 돌려도 정리된 행은 후보에서 빠진다.
- 이슈 본문의 `SELECT ... FOR UPDATE SKIP LOCKED`는 쓰지 않았다. 후보 선별은 읽기이고 쓰기는 CAS와 티켓 잠금으로 이미 안전하며, H2 테스트와 호환된다.

### 기존 만료 처리와의 경계

| | 대상 | 하는 일 |
|---|---|---|
| `reconcileRunning` (5초) | RUNNING 묶음 | Agent에 결과를 물어 반영 |
| `expireStale` (1분, 기본 30분) | 기한이 지난 RUNNING 묶음 | 결과 없이 `RESULT_TIMEOUT`으로 끝냄. 같은 트랜잭션에서 기록도 UNCERTAIN |
| 복구 워커 (5분, 기본 10분 이상 된 행) | 티켓이 더 이상 기다리지 않는데 진행 중으로 남은 기록 | 위 표대로 정리 |

같은 행을 두 경로가 동시에 만져도 모두 조건부 UPDATE라 한쪽만 반영된다. 복구 워커는 발행 시간 상한, Agent 재시작 때의 404 처리, `PUBLISH_RESULT_TIMEOUT` 조정을 하지 않는다(#302).

### 설정

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `PUBLISH_RECOVERY_ENABLED` | `true` | 워커 사용 여부 |
| `PUBLISH_RECOVERY_INTERVAL_MS` | `300000` | 실행 주기(5분) |
| `PUBLISH_RECOVERY_STALE_AFTER` | `10m` | 이 시간 이상 갱신이 없는 행만 후보 |
| `PUBLISH_RECOVERY_BATCH_SIZE` | `100` | 한 주기에 정리하는 최대 건수 |
| `PUBLISH_GITHUB_RECHECK_DELAY` | `3m` | GitHub 신규 이슈가 결과 모름일 때 다시 보내기 전 기다리는 시간 (#336). 복구 워커 설정은 아니지만 발행 기록 예약에 쓴다 |

`app.analysis.scheduling-enabled=false`면 다른 스케줄러와 함께 꺼진다.

### 한계

- 정상 경로(묶음이 RUNNING이고 티켓이 PUBLISHING)에서 Backend가 죽으면 기존 `expireStale`이 끝낼 때까지(기본 30분) 기다린다. 이를 앞당기는 것은 결과 대기 기한 조정이라 #302 범위다.
- 결과 모름 판단만 하고 외부 항목을 조회해 CONFIRMED로 올리지 않는다. 담당자 확인(Notion/Slack) 또는 Agent 재대조(GitHub)가 필요하다.
- 발행 생략이 UNCERTAIN으로 남은 경우 티켓은 SKIPPED로 끝나 있어 `resolve-outcome`(FAILED 티켓 전용)으로 풀 수 없다. 같은 키의 재발행은 Notion/Slack에서 막힌다. 드문 조합이고 중복보다 안전한 쪽을 택했다.
- V5 이전 묶음은 기록이 없어 대상이 아니다.

## 동시성 처리

- 새 예약: `INSERT ... ON CONFLICT DO NOTHING`. 같은 키를 동시에 넣으면 늦은 쪽은 먼저 넣은 쪽이 끝날 때까지 기다린 뒤 예외 없이 0행을 받는다(트랜잭션이 깨지지 않는다).
- 재예약: `UPDATE ... SET status='RESERVED', attempt_count=attempt_count+1 WHERE key=? AND status IN (FAILED[, UNCERTAIN])`. 영향받은 행이 1인 쪽만 외부 쓰기를 진행한다.
- 티켓 행 잠금(`findForUpdate`)이 이미 같은 티켓의 동시 요청을 줄 세운다. 발행 기록의 INSERT와 UPDATE 조건은 그 잠금을 우회해도 한쪽만 통과하게 하는 DB 수준 보장이다.
- 일괄 UPDATE는 영속성 컨텍스트를 갱신하지 않으므로, 바꾼 뒤의 상태는 엔티티가 아니라 스칼라 조회로 다시 읽는다.

## 범위와 남긴 것

PR1 범위: V5 테이블과 엔티티, 예약/접수/결과/확인 연결, 상태 전이 검증, 테스트(H2 + 실제 PostgreSQL 마이그레이션 테스트). PR2 범위: 복구 워커와 결과 모름 보존(V6). 아래는 이 이슈에서 하지 않은 것이다.

- RESERVED는 "아직 보내지 않았다"를 보장하지 않는다. SENT 표시는 Agent 호출 뒤에 하므로, 호출 직후 죽으면 보낸 건도 RESERVED로 남는다. 그래서 복구 워커는 RESERVED와 SENT를 모두 결과 모름으로 다룬다.
- V5 이전에 시작된 묶음과 이미 끝난 티켓은 발행 기록이 없다. 백필하지 않는다. 이미 끝난 발행은 티켓 상태가 막고, 진행 중이던 묶음의 결과는 발행 기록 없이도 반영된다.
- 이슈의 이중 쓰기(`github_issue_*` 동시 기록), 읽기 전환, `(target, external_id)` partial unique는 후속 릴리스다. 기존 중복 데이터 정리가 먼저 필요하다.
- 멱등키를 Agent 요청에 실어 외부 본문/속성에 마커로 넣는 일은 하지 않았다. Agent 계약은 바꾸지 않는다.
- 발행 경로의 시간 상한, Agent 재시작 시 결과 유실 처리, 결과 대기 기한(`PUBLISH_RESULT_TIMEOUT`) 조정은 #302 범위다. 복구 워커는 이를 다루지 않는다.
- 알림 댓글이 처음 실패한 시점(UPDATE와 함께)에는 COMMENT 행이 없다. 첫 재전송부터 COMMENT 행이 생긴다.
- `ON CONFLICT DO NOTHING`은 충돌 대상을 적지 않는다(H2 테스트 호환). `publications`의 유니크는 `(target, idempotency_key)`뿐이라 같은 의미다.

## 테스트

- 단위/통합(H2): `PublicationStatusTest`(전이 표 전체, 키 구성), `PublicationLedgerTest`(중복 예약, 동시 경쟁, 재예약, UNCERTAIN 정책, 확인 결과, 삭제 연쇄), `PublicationFlowTest`(HTTP 흐름: 예약 후 접수, 확정, 실패 재시도, 결과 모름, 발행 생략, 접수 취소, 댓글 재전송), `PublicationRecoveryTest`(복구 워커와 결과 모름 보존), `ProjectDeletionTest`.
- 마이그레이션(PostgreSQL 필요): `MigrationSchemaTest`가 빈 DB/기존 DB의 스키마 일치, V4 -> V5 적용 시 기존 데이터 보존, V6의 `outcome_unknown` 백필, 유니크/CHECK/FK/CASCADE, 실제 PostgreSQL에서의 동시 INSERT와 동시 예약을 검증한다. `MIGRATION_TEST_DB_URL`을 지정해 실행하고 개발 데이터가 있는 DB는 쓰지 않는다.
