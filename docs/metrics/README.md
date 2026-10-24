# 수정률, 승인율, 발행 성공률 지표

관련 이슈: #264. 입력은 [리비전과 상태 이력](../revisions-and-history.md)의 `task_item_revisions`, `ticket_status_history`, `task_items`뿐이다. 글자 단위 diff는 KPI에 쓰지 않는다. 값이 같은지 다른지만 본다.

| 파일 | 지표 |
|---|---|
| [approval-rate.sql](approval-rate.sql) | 승인율 |
| [edit-rate.sql](edit-rate.sql) | 항목 수정률, 필드별(제목, 담당자, 기한, 본문) 수정률 |
| [publish-success-rate.sql](publish-success-rate.sql) | 발행 성공률 |

쿼리는 PostgreSQL 전용이다 (`jsonb` 연산자, `DISTINCT ON`). 읽기 전용이고 파라미터가 없다. 결과는 프로젝트별(`project_id`) 한 줄과 전체 한 줄(`project_id`가 null)이다. 비율은 0~1 사이 소수 4자리이고 분모가 0이면 null이다. 기간으로 좁히려면 각 파일 안의 설명을 따라 `ticket_status_history.created_at` 조건을 더한다.

`MIGRATION_TEST_DB_URL`이 있을 때 도는 `MetricsQueryTest`가 고정 입력 데이터로 이 파일들을 실제 PostgreSQL에서 실행해 기대 수치를 확인한다. 로컬에서는 `MigrationSchemaTest`와 같이 `MIGRATION_TEST_DB_URL`로 개발 데이터가 없는 PostgreSQL을 가리키고 `./gradlew test --tests '*MetricsQueryTest'`로 돌린다 (전용 스키마 `mig_metrics`를 만들고 지운다).

## 용어: 현재 결정

승인율과 수정률은 티켓마다 "현재 결정"을 하나 정해서 센다. 현재 결정은 상태 이력에서 reason이 `CONFIRM`(승인), `REJECT`(거절), `REOPEN`/`RESTORE`/`REVISE`(다시 검토 대기로 돌아감) 중 가장 마지막인 전이다. 마지막이 `CONFIRM`이면 승인, `REJECT`면 거절이고, 다시 검토 대기로 돌아갔으면 결정 없음이다. 같은 시각이면 이력 번호가 큰 쪽이 나중이다.

- 수정하고 거절하거나, 확정했다가 다시 열어 둔 티켓은 아직 결정이 없다.
- 거절했다가 되돌려(`RESTORE`) 다시 승인했다면 마지막인 승인이 기준이다.
- 발행 실패 뒤 수정하러 되돌렸다면(`REVISE`) 다시 확정할 때까지 결정 없음이다.
- `to_status = 'CONFIRMED'`만으로 승인을 고르면 안 된다. 발행 접수가 취소되어 돌아온 `PUBLISHING -> CONFIRMED`(`PUBLISH_NOT_ACCEPTED`)도 같은 상태라 승인으로 잘못 잡힌다.

## 승인율

```
승인율 = 현재 결정이 승인인 티켓 / (현재 결정이 승인인 티켓 + 현재 결정이 거절인 티켓)
```

- 분모: 승인 + 거절. 결정 없는 티켓(검토 대기, 담당 지정 필요, 다시 열어 둔 티켓)은 아직 판단 전이라 뺀다.
- 승인한 뒤 발행에 실패하거나 발행이 생략되어도 승인으로 남는다. 승인율은 발행 결과가 아니라 담당자의 판단을 본다.
- 열 이름: `approved`, `rejected`, `decided`(분모), `approval_rate`.

## 항목 수정률

```
항목 수정률 = 최종본이 에이전트 원본과 다른 승인 티켓 / 승인 티켓 (V4 이후 생성 티켓만)
필드별 수정률 = 그 필드가 원본과 다른 승인 티켓 / 승인 티켓
```

- 분모: 현재 결정이 승인이고 V4 이후에 만들어진 티켓. 거절한 티켓은 수정본을 쌓지 않으므로 뺀다.
- 원본: `kind = 'AGENT'`, `rev = 1` 스냅샷. 최종본: `AGENT`/`USER_EDIT` 중 `rev`가 가장 큰 스냅샷. `PUBLISHED` 리비전은 쓰지 않는다.
- 항목 수정은 제목, 담당자, 기한, 본문 중 하나라도 원본과 다르면 1건이다. 한 티켓은 여러 필드를 고쳐도 항목 수정률에서는 1건이고, 필드별 수정률에는 필드마다 1건으로 들어간다.
- `USER_EDIT` 리비전이 있다는 사실만으로는 수정으로 세지 않는다. 발행 대상, 처리 유형, 그 밖의 본문 키만 바뀐 수정본이 있다.
- 비교하는 값 (스냅샷 키): 제목 `title`, 담당자 `assignee_member_id`, 기한 `content.due.text/date/time`, 본문 `content.description`.
- 정규화: 제목과 본문은 양끝 공백을 지우고, 기한 세 값은 없음(null)과 빈 문자열을 같게 본다. 카드 수정 API가 공백을 지우고 빈 기한을 빈 문자열로 저장하므로, 사용자가 고치지 않았는데 형식만 달라진 경우를 수정으로 세지 않기 위해서다. 그 밖의 차이(대소문자, 문장 부호 등)는 모두 수정이다.
- 담당자: 에이전트가 담당자를 찾지 못한 티켓(담당 지정 필요)을 업로더가 지정한 경우도 담당자가 원본과 달라진 것이라 수정으로 센다.
- 열 이름: `approved_items`(분모), `edited_items`, `edit_rate`, 필드별 `title_edited`/`title_edit_rate`, `assignee_edited`/`assignee_edit_rate`, `due_edited`/`due_edit_rate`, `description_edited`/`description_edit_rate`.

## 발행 성공률

```
발행 성공률 = 성공 티켓 / (성공 티켓 + 실패 티켓)
```

티켓 단위로 현재 상태(`task_items`)를 본다. 실패 뒤 다시 시도해 성공했다면 성공으로 센다 (최종 성공률). 첫 시도 성공률이 아니다.

| 구분 | 상태 |
|---|---|
| 성공 (분자) | `PUBLISHED`, `LINKED`, 알림 댓글까지 끝난 `UPDATED`(`publish_error_code`가 null) |
| 실패 (분모에만) | `FAILED`, 알림 댓글이 실패한 `UPDATED`(`publish_error_code`가 남음). 결과 모름도 `FAILED`라 실패로 센다 |
| 분모에서 제외 | `SKIPPED`(발행 생략: 성공으로 세지 않고 `skipped`로 따로 센다), `PUBLISHING`(진행 중), 아직 발행하지 않은 상태(`ASSIGNEE_REQUIRED`, `PENDING_REVIEW`, `CONFIRMED`, `REJECTED`) |

- 알림 댓글만 실패한 `UPDATED`는 이슈 본문 갱신 자체는 끝났지만, 사용자 노출 상태가 "발행됨"이 아니라 "실패/확인 필요"이므로 같은 기준으로 성공에서 뺀다.
- Notion/Slack의 결과 모름을 담당자가 "반영됨"으로 확인하면 `PUBLISHED`가 되어 성공으로 센다.
- 열 이름: `succeeded`, `failed`, `attempted`(분모), `publish_success_rate`, `skipped`.

## 알려진 한계

- V4 이전 티켓: 상태 이력과 `PUBLISHED` 리비전이 없고, `AGENT` 리비전은 V4 시점의 값이라 원본이 아닐 수 있다. 그래서 수정률은 생성 전이(`from_status`가 null인 이력)가 있는 V4 이후 생성 티켓만 센다. V4 이전 티켓도 V4 이후에 승인/거절하면 상태 이력이 생겨 승인율에는 들어간다. 발행 성공률은 현재 상태만 보므로 V4 이전 티켓도 센다. 그래서 세 지표의 모집단이 조금씩 다를 수 있다.
- 수정률은 확정 시점의 최종본만 비교한다. 확정 전에 몇 번 고쳤는지, 고쳤다가 되돌렸는지는 알 수 없다 (되돌려서 원본과 같아지면 수정이 아니다).
- 재분석은 supersede(`supersedes_item_id`)가 아직 없어 같은 업무의 이전 분석 티켓과 새 티켓이 각각 다른 티켓으로 센다.
- 발행 성공률은 `task_items`의 현재 상태라서 과거 시점의 성공률을 다시 계산할 수 없다. 기간별 추이가 필요하면 상태 이력(`PUBLISH_RESULT` 등 reason)을 기반으로 한 쿼리를 따로 추가해야 한다.
- 에이전트 정밀도, 담당자/기한 오류율(#266)은 이 문서의 범위가 아니다. 이 데이터(`AGENT` 대비 `USER_EDIT`의 필드별 차이)로 계산할 수 있다.
