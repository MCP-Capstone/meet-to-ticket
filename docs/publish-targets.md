# 발행 대상별 매핑과 상태/재발행 정책

티켓은 발행 대상(GitHub, Notion, Slack) 중 하나로 발행한다. 이 문서는 대상별 필드 매핑, 필요한 권한, 재발행 규칙, 결과 불명 처리, 사용자 노출 상태를 한곳에 정리한다. 새 발행 대상을 추가할 때는 "신규 대상 추가 체크리스트"를 따른다.

표기 규칙: "현재 구현"은 코드로 확인한 동작이고, "정책 목표"는 아직 코드에 없는 합의 방향이다. 둘을 섞어 읽지 않는다.

관련 코드 위치:

| 영역 | 위치 |
|---|---|
| 발행 대상/상태 enum | `backend/.../analysis/PublishTarget.java`, `TicketStatus.java` |
| 일괄 발행, 결과 저장, 기한 정리 | `backend/.../analysis/PublishService.java`, `PublishBatchExpiry.java` |
| 대상 변경, 처리 유형 변경 | `backend/.../analysis/TicketService.java` |
| 대상별 발행 실행 | `agent/batch_publish.py`, 본문 조립 `agent/publisher.py` |
| 외부 API 호출 | `business-mcp/integrations/github`, `notion`, `slack` |

## 1. 발행 흐름 요약 (현재 구현)

1. Agent가 티켓마다 `publish_target`을 제안한다. 값이 없으면 GitHub다. 회의 결과 공지는 회의당 1건이 Slack 대상 티켓으로 따로 만들어진다.
2. 담당자가 카드에서 본문/기한/담당자를 고치고, 검토 대기 상태에서만 발행 대상과 처리 유형을 바꾼다. GitHub 이외 대상은 처리 유형이 신규 생성(CREATE)으로 고정된다.
3. 담당자가 확인한 티켓만 한 묶음(batch)으로 Agent에 보낸다. 보내는 순간 티켓은 발행 중이 되어 중복 클릭이 막힌다.
4. Agent가 대상별로 발행하고 항목별 결과를 backend에 보고한다. backend는 결과를 티켓 상태로 저장한다. 한 티켓의 실패는 묶음의 다른 티켓을 막지 않는다.

## 2. 대상별 매핑

### 2.1 프로젝트 설정과 서버 토큰

| 대상 | 프로젝트별 설정 | 서버 토큰 (`business-mcp/.env`) | 필요한 권한 |
|---|---|---|---|
| GitHub | 저장소 owner/repo | `GITHUB_TOKEN` (서비스 계정 1개를 모든 저장소에 사용) | 서비스 계정이 프로젝트 저장소에 초대되어 Issues 읽기/쓰기 가능. `GITHUB_ALLOWED_REPOS`를 채우면 그 저장소만 허용 (선택) |
| Notion | 기획 페이지 URL (`notionPageUrl`) | `NOTION_TOKEN` (Integration 토큰) | 기획 상위 페이지를 해당 Integration에 공유해야 한다. 토큰만으로는 페이지에 접근할 수 없다 |
| Slack | 채널 ID (`slackChannelId`) | `SLACK_BOT_TOKEN` (봇 토큰 1개) | 봇을 프로젝트 채널에 초대하고 `chat:write`, `channels:read`, `groups:read`, `users:read`, `users:read.email` 권한 부여 (`business-mcp/.env.example` 기준) |

토큰 값은 저장소에 커밋하지 않는다. `.env.example`을 복사해 `.env`에 채운다. 토큰과 대상 범위는 모델(LLM)에 노출하지 않고 MCP 서버가 환경 변수로만 읽는다.

### 2.2 필드 매핑

| 항목 | GitHub | Notion | Slack |
|---|---|---|---|
| 신규 생성 | Issue 생성 (`create_issue`) | 페이지 생성 (`create_page`) | 메시지 게시 (`post_message`) |
| 제목 | 티켓 제목 | 티켓 제목 (페이지 제목) | 없음 (본문에 머리글 포함) |
| 본문 | 작업 내용, 상세 작업, 완료 조건, 근거, 담당, 기한, 관련/상위 이슈, 확인 필요, 회의 배경 | GitHub 본문과 같은 순서. GitHub 전용 칸(라벨, 관련/상위 이슈, `@계정`)은 제외 | 공지 문구(`slack.text`, 비어 있으면 `description`)를 게시. 본문의 정확한 `@이름`은 Slack 연결이 확인된 검토 요청 대상만 `<@사용자ID>` 멘션으로 바뀐다 |
| 담당자 | 멤버에 저장된 GitHub 계정만 assignee로 지정. 계정이 없으면 본문에 "GitHub 계정 미매핑"으로 표기 | 본문 "담당" 칸에 이름만 표기 | 검토 요청 대상(reviewers) 중 Slack 사용자 ID가 확인된 사람만 멘션, 나머지는 이름 텍스트 |
| 라벨 | 티켓 라벨 (저장소에 없는 라벨은 제외되고 `dropped_labels`로 보고) | 없음 | 없음 |
| 기존 항목 연결 | `LINK`: 열린 기존 Issue에 댓글 | 불가 (신규만) | 불가 (신규만) |
| 기존 항목 갱신 | `UPDATE`: 열린 기존 Issue 본문 끝에 변경 섹션 추가, 라벨 추가, 알림 댓글 | 불가 | 불가 |
| 결과 저장 | Issue 번호/URL | 페이지 URL | 메시지 링크 |
| 저장 위치 | 응답 필드 `githubIssueNumber`, `githubIssueUrl` | `githubIssueUrl` 칸에 페이지 URL을 담음 (스키마는 그대로) | `githubIssueUrl` 칸에 메시지 URL을 담음 |

보조 사실:

- 연결/갱신은 중복 판단이 찾은 "열린" 기존 Issue에만 가능하다. 닫힌 Issue는 다시 열지 않는다. 담당자가 임의의 Issue 번호를 지정할 수 없다.
- Notion 페이지는 `NOTION_ROOT_MISSING`(프로젝트에 기획 페이지 URL이 없음)이면 Notion을 호출하지 않고, GitHub Issue로 대신 만들지도 않는다.
- Slack은 `slackChannelId`가 없으면 `INVALID_ARGUMENT`로 실패한다. 공지 문구가 비어 있어도 실패한다.
- 최종 발행 본문 미리보기 API는 현재 GitHub 대상만 지원한다 (`publication_preview`).

### 2.3 재발행 규칙 (현재 구현)

공통:

- 재시도 API(`PublishService.retry`)는 담당자와 `FAILED` 상태를 검사하고, `MANUAL_CHECK`이면 409로 막는다. 재시도할 수 없는 실패(`retryable=false`이고 결과 모름도 아님)는 `409 PUBLISH_RETRY_NOT_ALLOWED`로 거절하고 화면 버튼을 비활성화한다. 원인/설정을 해결한 뒤 수정 후 재승인 경로를 이용한다. GitHub 결과 모름(`RESULT_TIMEOUT`, `RESULT_MISSING`, 묶음 전체 실패)은 담당자 확인 후 다시 시도할 수 있고 Agent가 재대조한다 (#291).
- 기존 Issue 갱신(`UPDATE`)에서 알림 댓글만 실패하면 `UPDATED + COMMENT_FAILED`를 유지한다. 담당자는 전용 `POST /api/tickets/{ticketId}/retry-comment`로 댓글만 다시 보낼 수 있다. 실제 갱신한 이슈 번호를 사용하며 LLM/재대조/본문 PATCH를 실행하지 않는다. `meettoticket:update-comment` 표식을 먼저 확인하고, 없으면 표식 없는 과거 알림 문구도 찾는다. 댓글 조회가 불완전하면 전송하지 않고 `UPDATED + MARKER_CHECK_INCOMPLETE`(재전송 불가, GitHub 직접 확인)로 남긴다. 응답 유실에도 본문 성공은 유지한다 (#291).
- 실패 티켓은 재시도 대신 "수정하려고 검토 대기로 되돌리기"(`TicketService.revise`)도 할 수 있다. 되돌리면 지난 발행 결과는 비우고, 결과 불명이었다는 사실만 `previousOutcomeUnknown`으로 남겨 다음 발행에서 재대조를 강제한다. `MANUAL_CHECK` 실패는 확인 결과를 먼저 남겨야 하므로 되돌리기도 409다.
- 결과 불명이 남아 있으면(`mustRecheckOnPublish`) 검토 대기에서도 발행 대상을 바꿀 수 없다 (`PUBLISH_TARGET_LOCKED`, 409). 같은 업무가 두 대상에 생기는 것을 막으려는 잠금이며, 본문 수정은 할 수 있다. 그 대상에서 결론(GitHub 재시도 재대조, Notion/Slack 확인 결과)을 내면 풀린다.
- 확실한 성공이 오면 지난 결과 불명 표시는 풀린다. 실패가 오면 이어진다.
- 결과 불명 해소 방식은 `PublishTarget.recheckable`로 나뉜다. GitHub는 `true`라 `PublishResolution.RETRY`(다시 시도하면 Agent가 재대조)이고, Notion과 Slack은 `false`라 결과 불명일 때 `MANUAL_CHECK`(확인 결과를 남겨야 함)다. 확인 결과는 담당자만 `POST /api/tickets/{ticketId}/resolve-outcome`으로 남긴다. `applied=true`이면 발행됨으로 끝내고(선택으로 대상의 https 링크 저장), `applied=false`이면 결과 불명 표시를 풀고 `VERIFIED_NOT_APPLIED`(`retryable=true`) 실패로 바꿔 다시 시도하면 차단 없이 발행한다. GitHub 티켓이나 `MANUAL_CHECK`가 아닌 실패는 409다.
- Agent가 접수하지 않은 것이 확실한 경우(접수 단계 오류)는 티켓을 발행 전 상태(확인 또는 실패)로 되돌려 다시 누를 수 있게 한다.

| 대상 | 결과 불명 뒤 재발행 시 중복 방지 방식 |
|---|---|
| GitHub 신규 | 같은 `work_key`로 만들어진 열린 Issue를 검색해 제목까지 같으면 새로 만들지 않고 그 Issue를 결과로 쓴다 |
| GitHub 연결/갱신 | Issue 본문/댓글에서 숨은 표식(`meettoticket:link meeting=... work_key=...` 또는 `meettoticket:update meeting=... work_key=...`)을 찾는다. 있으면 다시 쓰지 않고 성공으로 처리한다. 댓글이 많아 끝까지 확인하지 못하면 `MARKER_CHECK_INCOMPLETE`로 막는다 |
| Notion 신규 | 이미 만들어졌는지 찾아볼 도구가 없다. 결과 불명 뒤 재시도는 중복 페이지 방지를 위해 보내지 않고 `NOTION_VERIFY_REQUIRED`로 실패시킨다. 이 실패는 `outcomeUnknown=false`, `retryable=false`지만 해소 방식이 `MANUAL_CHECK`라서, 담당자가 '회의 후속 티켓' 폴더에서 페이지 유무를 확인하고 `resolve-outcome`으로 결과를 남기면 발행됨 또는 재시도 가능으로 풀린다 |
| Slack 신규 | Notion과 같다. 결과 불명 뒤 재시도는 중복 게시 방지를 위해 보내지 않고 `SLACK_VERIFY_REQUIRED`로 실패시키며, 담당자가 Slack 채널에서 공지 유무를 확인하고 `resolve-outcome`으로 결과를 남겨야 풀린다. 자동으로 반영 여부를 찾아보지는 않는다 |

## 3. 사용자 노출 상태와 내부 상태 대응

### 3.1 사용자 노출 상태 4종

아래 표는 사용자 노출 4종 상태와 내부 `TicketStatus`의 대응이다. 백엔드는 이 대응을 `TicketDisplayStatus.of`로 파생해 티켓 응답의 `displayStatus` 필드로 준다 (#264, 기존 `status`는 그대로). 현재 화면은 아직 `displayStatus`를 쓰지 않고 내부 상태 라벨을 그대로 보여 준다.

| 사용자 노출 상태 | 내부 `TicketStatus` | 비고 |
|---|---|---|
| 검토 대기 | `ASSIGNEE_REQUIRED`, `PENDING_REVIEW`, `CONFIRMED` | 담당 지정 필요, 담당자 검토 중, 확인 후 발행 전. 확인(`CONFIRMED`)은 발행 대기이므로 이 묶음에 둔다 |
| 발행 중 | `PUBLISHING` | 묶음 결과를 기다리는 중. 같은 티켓을 다시 담을 수 없다 |
| 발행됨 | `PUBLISHED`, `LINKED`, `UPDATED` | 신규 생성/기존 항목 연결/기존 항목 갱신. Slack 게시는 에이전트 상태 `POSTED`를 `PUBLISHED`로 저장한다. 단, 알림 댓글이 실패해 오류 코드(`COMMENT_FAILED`)가 남은 `UPDATED`는 아래 실패/확인 필요다 |
| 실패/확인 필요 | `FAILED`, `SKIPPED`, `UPDATED` + 오류 코드(`COMMENT_FAILED`) | `FAILED`는 3.2의 구분표를 따른다. `outcomeUnknown`만으로 안전한 재시도 여부를 판단하면 안 된다. `SKIPPED`(발행 생략, 외부에 아무것도 만들지 않음)와 알림 댓글 실패는 발행됨에 합치지 않고 확인이 필요한 쪽에 둔다 |
| (4종 밖) | `REJECTED` | 담당자가 거절한 티켓. 발행 대상이 아니므로 4종 상태에 넣지 않고 별도 표시한다 (`displayStatus`는 null) |

### 3.2 FAILED 세부 구분

| `outcomeUnknown` | `retryable` | 의미 | 예 |
|---|---|---|---|
| true | 대개 true | 대상에 반영됐을 수 있음. 확인 후 재시도 | `RESULT_UNKNOWN`, `RESULT_TIMEOUT`, `RESULT_MISSING`(이 둘은 `retryable=false`로 저장됨), `RESULT_LOST`(Agent 재시작), 처리 중 마감에 걸린 `PUBLISH_DEADLINE` |
| false | true | 대개 반영되지 않은 실패로 재시도 가능 | `GITHUB_RATE_LIMIT`, `NOTION_RATE_LIMIT`, 마감이 지나 보내지 않은 `PUBLISH_DEADLINE`. `MCP_UNAVAILABLE`은 조건부(5절 참고) |
| false | false | 재시도해도 소용없거나 대상에 이미 있을 수 있음. 안전한 재시도가 아니다 | 권한/설정 오류(`GITHUB_FORBIDDEN`, `NOTION_ROOT_MISSING`), 예외: `NOTION_VERIFY_REQUIRED`, `SLACK_VERIFY_REQUIRED`, `MARKER_CHECK_INCOMPLETE`는 대상에 이미 반영됐을 수 있는데도 `outcomeUnknown=false`. 앞의 둘은 `MANUAL_CHECK`로 확인 결과를 남겨야 하고, 확인 결과가 `applied=false`이면 `VERIFIED_NOT_APPLIED`(`outcomeUnknown=false`, `retryable=true`)가 된다 |

### 3.3 현재 구현과 차이

- 화면은 4종으로 합치지 않고 내부 상태 라벨을 그대로 쓴다 (`TicketCard.tsx`: 담당 지정 필요, 검토 대기, 확인, 거절, 발행 중, 발행됨, 연결됨, 갱신됨, 발행 생략, 실패). 백엔드는 `displayStatus`를 이미 주므로 화면은 필요할 때 이 값으로 바꿀 수 있고, 이 문서가 대응 기준이다.
- `displayStatus`는 티켓 응답(`MyTicketService.Ticket`: 내 티켓, 전송 결과, 티켓 동작 응답)에만 있다. 발행 묶음 결과의 티켓 항목(`PublishService.Entry`)에는 아직 없다.
- 결과 화면은 묶음 요약(`Summary`)에서 발행됨/연결됨/갱신됨/실패/발행 중을 따로 센다.
- 묶음(`PublishBatchStatus`)은 `RUNNING`, `SUCCEEDED`, `PARTIAL_FAILED`, `FAILED`이며 티켓 상태와 별개다.

## 4. 결과 불명(UNCERTAIN) UX 정책

결과 불명은 "발행 요청을 보냈지만 성공/실패를 알 수 없는" 상태다.

### 4.1 현재 구현

- 원인: 쓰기 요청을 보낸 뒤 예외가 난 경우(Agent 내부 `UNKNOWN` 상태), Agent 발행 마감에 걸린 처리 중 티켓, Agent 보고에 빠진 티켓, Agent가 재시작돼 잃은 묶음, 기한 안에 보고가 오지 않은 묶음.
- 저장: 티켓은 `FAILED` + `outcomeUnknown=true`로 저장된다 (`RESULT_UNKNOWN`, `RESULT_MISSING`, `RESULT_TIMEOUT`, `RESULT_LOST`, `PUBLISH_DEADLINE` 등의 오류 코드).
- 시간 상한 (#302): Agent는 MCP 요청 하나를 `MCP_CALL_TIMEOUT_SECONDS`(기본 90초), 묶음 전체를 `PUBLISH_DEADLINE_SECONDS`(기본 600초) 안에 끝낸다. 마감에 걸리면 처리 중이던 티켓은 결과 모름, 시작 전 티켓은 보내지 않은 재시도 가능 실패(`PUBLISH_DEADLINE`)다.
- 잃은 묶음: Agent는 묶음 기록을 메모리에만 두므로 재시작하면 처리 중이던 묶음도 `GET /publish/{batchId}`가 404다. Backend는 접수 뒤 유예 시간(`app.publish.lost-grace`, 기본 1분)이 지난 묶음의 404를 잃은 묶음으로 보고 다음 결과 조회 주기(5초)에 `RESULT_LOST` 결과 모름으로 끝낸다.
- 기한 정리: 접수 확인에 실패했거나 Agent가 보고하지 못한 묶음은 기한(`app.publish.result-timeout`, 기본 15분)이 지나면 `PublishBatchExpiry`가 1분(`app.publish.expire-interval-ms`, 기본 60000) 주기로 `RESULT_TIMEOUT` 실패로 끝낸다. Agent 마감보다 길게 두는 마지막 안전망이며, 그 전까지 티켓은 발행 중에 머문다.
- 화면(GitHub 등 `RETRY`): 결과 불명 안내와 확인 체크박스를 표시한다. 재시도 가능(`retryable=true`이거나 결과 불명)하고 확인한 경우만 "다시 시도"가 활성화된다. `RESULT_TIMEOUT`/`RESULT_MISSING` 같은 결과 불명도 담당자가 확인한 뒤 다시 시도할 수 있고, 지난 결과 불명 표시가 유지돼 Agent가 재대조한다 (2.3 참고, #291). 수정 후 재승인 경로를 택해도 결과 불명 표시는 남아 재대조를 강제한다.
- 화면(Notion/Slack, `MANUAL_CHECK`): 다시 시도 대신 "이미 반영됨"/"반영되지 않음" 버튼과 선택 링크 입력을 보여 준다. 각각 확인 창을 거쳐 `resolve-outcome`을 호출한다. 결과 화면(`PublishBatchResult.tsx`)에는 "{도구명}에 반영됐을 수 있습니다. 다시 시도하기 전에 확인하세요."라는 다른 문구가 있다.
- 자동 회수(자동으로 반영 여부를 확인해 상태를 바로잡는 것)와 "발행 확인 중" 표시, 5분 뒤 수동 확인 전환은 코드에 없다. 코드가 하는 재대조는 사용자가 다시 시도할 때 대상별로 돌아간다 (2.3 참고).

### 4.2 정책 목표 (미구현)

| 단계 | 동작 |
|---|---|
| 결과 불명 직후 | 사용자에게 실패로 보이지 않고 "발행 확인 중"으로 표시한다 |
| 자동 회수 | 시스템이 대상(GitHub, Notion, Slack)에서 반영 여부를 먼저 확인해 발행됨 또는 실패로 확정한다 |
| 5분 뒤 | 자동 회수로 확정되지 않으면 "확인 필요"로 바꾸고 사용자가 대상에서 직접 확인한 뒤 재시도하게 한다 |

이 목표를 구현하려면 Notion과 Slack에도 반영 여부 조회 수단이 필요하다 (현재 Notion과 Slack은 조회 도구가 없어 담당자 수동 확인에 의존한다).

## 5. 알려진 한계와 확인 필요 항목

- Slack 공지 일괄 발행은 backend(`PublishService.publishable`)와 Agent(`batch_publish.py` SLACK 분기)에 구현되어 있다.
- #291: HTTP 연결/풀 확보 실패처럼 미전송이 확인되는 경우만 `MCP_UNAVAILABLE`로 분류한다(langchain-mcp-adapters가 ExceptionGroup으로 감싸도 안쪽을 본다). 쓰기 요청의 읽기/쓰기/연결 종료 오류는 MCP에서 `[RESULT_UNKNOWN]`으로 전달하고 Agent가 `UNKNOWN`으로 보존한다. 단계가 불명인 Agent 쓰기 예외도 보수적으로 `UNKNOWN`이다. Slack 게시 후 링크 조회 실패는 확인 대상으로 남긴다. Notion 페이지 생성 후 나머지 본문 추가가 4xx로 거절되면 만든 페이지를 보관 처리하고 원래 오류로 실패시키며, 시간 초과/5xx/보관 실패처럼 모호하면 확인 대상으로 남긴다.
- 재시도 제한과 알림 댓글 복구 경로는 2.3을 참고한다 (#291).
- 댓글 표식은 이번 수정 이후의 UPDATE 알림에 포함된다. 과거 표식 없는 댓글은 같은 회의의 알림 문구로 찾는다. Agent 재시작/장시간 실행과 겹치는 경쟁 조건의 정확히 한 번 처리는 보장하지 않는다. DB 멱등키와 예약 기록은 [발행 기록과 멱등키](publications.md)(#263 PR1)가 맡고, 멈춘 예약의 복구 워커는 PR2 범위다.
- 해소됨: Slack 메시지 링크는 Agent가 MCP 응답의 `permalink`를 읽어 `message_url`로 저장한다 (`batch_publish.py`). Notion/Slack 결과 불명 뒤 재발행 차단과 수동 확인 해소 흐름(`resolve-outcome`)은 2.3에 있다.
- 결과 화면 링크 허용 도메인은 `frontend/src/batchPublish.ts`의 `safePublishUrl`이 대상별로 검사한다.

## 6. 정책 수치

중복률 기준, 자동 발행 기준 등 정책 수치는 이 문서에 복제하지 않는다. 단일 기준은 [평가셋과 회귀 평가](evaluation.md)의 "결정 기록 (목표 지표)" 표다.

## 7. 신규 발행 대상 추가 체크리스트

1. `PublishTarget`에 값을 추가하고, backend `PublishService.publishable`에 포함한다.
2. 프로젝트 설정 필드(대상 위치 식별자)와 연결 확인 API를 추가한다.
3. business-mcp에 `integrations/<대상>/`을 만들고 토큰은 `.env`로만 읽는다. 오류는 `[코드] 메시지` 형태로 구분한다.
4. Agent `batch_publish.py`에 분기를 추가하고, 결과 상태/링크 필드를 보고 계약(`PublishItemResult`)과 backend `record`에 매핑한다.
5. 이 문서의 2.1, 2.2, 2.3 표를 채우고, 결과 불명 뒤 중복 방지 방식을 정한다.
6. 결과 화면 링크 허용 도메인(`safePublishUrl`)과 도구 이름 표시(`toolName`)를 추가한다.
7. 필요한 권한과 토큰 이름을 `business-mcp/.env.example`과 README 구성표에 반영한다.
