# 리비전과 상태 이력

관련 이슈: #264 (PR1), #267에서 이관한 승인 이력. 지표 쿼리, 상태 파생, `review_status`/`publish_status` deprecated 표시는 PR2 범위다.

## 왜 필요한가

`task_items.content_json`은 카드 수정으로 덮어써져 에이전트 원본이 남지 않고, 상태가 바뀐 시각과 사람도 남지 않았다. 수정률, 승인율, 확정 소요시간 같은 지표와 감사, 평가셋 환류는 이 기록이 있어야 계산할 수 있다.

## 테이블 (Flyway V4)

### task_item_revisions

티켓의 시점별 전체 스냅샷. 쌓은 뒤에는 고치지 않는다.

| 컬럼 | 설명 |
|---|---|
| task_item_id, rev | 티켓과 티켓 안의 번호(1부터). `unique(task_item_id, rev)` |
| kind | `AGENT` 에이전트 원본, `USER_EDIT` 확정한 수정본, `PUBLISHED` 발행 성공 최종본 (CHECK) |
| snapshot | jsonb. 아래 형식 |
| schema_version | 스냅샷 형식 버전. 지금은 1 |
| prompt_version, model | 에이전트가 결과에 실어 보내지 않아 지금은 항상 null (알려진 한계) |
| actor_user_id | 수정/발행한 사용자. AGENT는 null. users에 FK를 걸지 않는다 |
| created_at | 만든 시각 |

스냅샷(schema_version 1)은 SQL로 꺼내기 쉽게 키를 snake_case로 둔다.

```
{ "title", "assignee_member_id", "assignee_name", "publish_target", "processing_type",
  "content": { content_json 전체 },
  "result": { "status", "processing_type", "github_issue_number", "url", "error_code" } }   // PUBLISHED만
```

### ticket_status_history

`ticket_status`가 바뀔 때마다 한 줄. 쌓은 뒤에는 고치지 않는다.

| 컬럼 | 설명 |
|---|---|
| task_item_id | 티켓. 인덱스 `(task_item_id, created_at)` |
| from_status | 이전 상태. 티켓이 처음 만들어질 때만 null (CHECK) |
| to_status | 바뀐 상태 (CHECK) |
| actor_user_id | 바꾼 사용자. 에이전트 결과 저장, 발행 결과 반영처럼 시스템이 바꾼 경우는 null |
| reason | 원인 코드 |
| created_at | 바뀐 시각 |

두 테이블 모두 `task_item_id`가 `task_items`를 `ON DELETE CASCADE`로 참조한다. 프로젝트 삭제가 JPQL 일괄 삭제여서 DB 쪽에서 함께 지운다.

`TicketStatus`에 값을 더하면 `task_items.ticket_status`의 CHECK와 함께 `ticket_status_history`의 두 CHECK도 새 마이그레이션에서 고쳐야 한다. 빠뜨리면 `MigrationSchemaTest`가 엔티티와 스키마 차이로 실패한다.

## 언제 기록하는가

기록은 `TicketHistoryRecorder`가 하고, 각 서비스가 상태를 바꾼 직후 같은 트랜잭션에서 부른다.

| 시점 | 리비전 | 상태 이력 (reason) |
|---|---|---|
| Agent 결과로 티켓 생성 (`AnalysisStore.apply`) | AGENT 1건 | null -> 초기 상태 (`AGENT_RESULT`) |
| 대표 담당 지정 (`DispatchService.assign`) | - | ASSIGNEE_REQUIRED -> PENDING_REVIEW (`ASSIGN`) |
| 확인/거절/다시 열기/되돌리기/수정하러 되돌리기 (`TicketService`) | 확인 시 USER_EDIT | `CONFIRM`, `REJECT`, `REOPEN`, `RESTORE`, `REVISE` |
| 일괄 발행에 담기 (`PublishService.prepare`) | - | `PUBLISH_REQUESTED` |
| Agent가 접수하지 않은 것이 확실해 되돌림 (`PublishService.abort`) | - | `PUBLISH_NOT_ACCEPTED` |
| 발행 결과 반영 (`PublishService.applyResult`) | 확정이면 PUBLISHED | 실패는 오류 코드, 그 외 `PUBLISH_RESULT` |
| 결과 모름을 반영됨으로 확인 (`TicketService.resolveOutcome`) | PUBLISHED | `OUTCOME_VERIFIED` |

### USER_EDIT는 확정(confirm) 시점에만

카드 수정 API(`PATCH /api/tickets/{id}`, 제목/본문/담당자/기한)는 호출할 때마다 `content_json`을 덮어쓰고, 검토 대기에서만 할 수 있다. 사용자가 수정을 끝냈다고 선언하는 동작은 검토 대기 -> 확인(`confirm`)이고, 확인한 티켓만 발행 대상이 된다. 그래서 확정 시점에 최종 편집본을 한 번 기록한다. 수정 호출마다 쌓지 않는다.

- 직전 리비전과 비교해 제목, 담당 멤버, 발행 대상, 처리 유형, 본문 중 하나라도 달라졌을 때만 쌓는다. 고친 것이 없으면 쌓지 않는다. 그래서 USER_EDIT가 없으면 "에이전트 원본 그대로 승인"이다.
- 다시 열어 고치고 다시 확정하면 그때 달라진 값이 있으면 한 건 더 쌓는다. 확정 한 번에 최대 한 건이다.
- 수정하고 거절한 티켓은 수정본을 쌓지 않는다.
- 발행 대상/처리 유형 변경도 사용자가 정한 값이라 수정본 비교에 포함한다.
- 대표 담당 지정(업로더)이 content_json과 담당자를 바꾸므로, 담당자를 찾지 못했던 티켓은 확정할 때 담당 멤버가 원본과 달라진 수정본이 된다.

### PUBLISHED는 발행 확정 시 1건

PUBLISHED, LINKED, UPDATED로 확정되면 발행 결과(`result`)를 담은 최종본을 티켓당 1건만 쌓는다. 이미 있으면 다시 쌓지 않는다. 다음은 쌓지 않는다.

- 발행 생략(SKIPPED)과 발행 실패(FAILED)
- 알림 댓글만 다시 보내는 재전송 (이미 확정된 UPDATED의 후속)
- 결과 모름을 "반영되지 않음"으로 확인한 경우 (상태가 그대로)

## 승인 이력

누가 언제 CONFIRMED로 승인했는지는 `ticket_status_history`에서 `to_status = 'CONFIRMED'`인 줄의 `actor_user_id`, `created_at`이다. 승인 시점의 수정 내용은 같은 시각의 USER_EDIT 리비전(없으면 원본 그대로)이다. API는 마지막 승인을 `approval`로 따로 준다.

## 조회 API

`GET /api/tickets/{ticketId}/history` (OpenAPI operationId `getTicketHistory`). 읽기 전용이고 티켓 담당자나 회의록을 올린 사람만 볼 수 있다 (그 밖은 403 `TICKET_HISTORY_FORBIDDEN`, 없는 티켓은 404).

```
{ ticketId,
  revisions:     [{ rev, kind, snapshot, schemaVersion, promptVersion, model, actor{userId,name}|null, createdAt }],
  statusHistory: [{ from, to, actor{userId,name}|null, reason, createdAt }],
  approval:      { actor, approvedAt } | null }
```

발행된 티켓은 `revisions`의 AGENT(원본)와 PUBLISHED(최종본)를 모두 볼 수 있다. 프론트는 아직 이 API를 쓰지 않는다.

## 기존 데이터 백필

- 리비전: 모든 기존 티켓에 AGENT 리비전 1건(rev=1)을 `INSERT ... SELECT`로 만든다. 만든 시각은 분석을 마친 시각(없으면 시작 시각)이다.
- 상태 이력: 백필하지 않는다. 지난 전이의 시각과 행위자는 알 수 없고, 현재 상태 한 줄을 지금 시각으로 넣으면 확정/발행 소요시간과 승인 이력이 사실과 다르게 보인다. V4 이후의 전이부터 기록한다.
- `content_json`이 JSON으로 읽히지 않는 티켓이 있으면 V4는 아무것도 바꾸지 않고 해당 티켓 ID와 함께 중단한다 (V2와 같은 방식).

## 알려진 한계

- 백필한 AGENT 리비전은 V4 시점의 현재 값이다. V4 이전에 이미 수정된 티켓은 에이전트 원본이 이미 사라져 있다.
- V4 이전 티켓은 상태 이력이 없고, 이미 발행된 티켓도 PUBLISHED 리비전이 없다.
- `prompt_version`, `model`은 Agent가 백엔드로 보내는 결과에 없어 비어 있다. 보내기 시작하면 AGENT 리비전 생성 시 채운다.
- 확정 전의 수정 과정(몇 번 고쳤는지)은 남지 않는다. 확정 시점의 최종본만 남는다.
- 재분석 supersede, publications/멱등키(#263), 지표 쿼리와 4상태 파생(PR2)은 이 범위가 아니다.
- 보존 기간(이력 1년 초안)에 따른 삭제는 아직 없다. 지금은 티켓을 지울 때만 함께 지워진다.
