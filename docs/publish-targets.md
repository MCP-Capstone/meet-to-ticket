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
| 본문 | 작업 내용, 상세 작업, 완료 조건, 근거, 담당, 기한, 관련/상위 이슈, 확인 필요, 회의 배경 | GitHub 본문과 같은 순서. GitHub 전용 칸(라벨, 관련/상위 이슈, `@계정`)은 제외 | 공지 문구(`slack.text`)를 그대로 게시 |
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

- 재시도 API(`PublishService.retry`)는 담당자와 `FAILED` 상태만 검사하고 `retryable` 값은 보지 않는다. `retryable=false`인 실패(예: `NOTION_ROOT_MISSING`)도 API로 반복 호출할 수 있고, 화면의 "다시 시도" 버튼도 `retryable`로 막지 않는다 (결과 불명일 때만 확인 체크박스가 필요하다). 원인을 고치지 않으면 같은 실패가 반복된다. 별도 이슈로 관리 필요.
- 기존 Issue 갱신(`UPDATE`)에서 본문 섹션은 추가됐지만 알림 댓글만 실패하면 티켓은 `UPDATED` + `COMMENT_FAILED` + `retryable=true`로 남는다. 재시도 API는 `FAILED`만 받으므로 이 티켓은 재시도로 댓글을 다시 보낼 수 없다. 별도 이슈로 관리 필요.
- 실패 티켓은 재시도 대신 "수정하려고 검토 대기로 되돌리기"(`TicketService.revise`)도 할 수 있다. 되돌리면 지난 발행 결과는 비우고, 결과 불명이었다는 사실만 `previousOutcomeUnknown`으로 남겨 다음 발행에서 재대조를 강제한다.
- 확실한 성공이 오면 지난 결과 불명 표시는 풀린다. 실패가 오면 이어진다.
- Agent가 접수하지 않은 것이 확실한 경우(접수 단계 오류)는 티켓을 발행 전 상태(확인 또는 실패)로 되돌려 다시 누를 수 있게 한다.

| 대상 | 결과 불명 뒤 재발행 시 중복 방지 방식 |
|---|---|
| GitHub 신규 | 같은 `work_key`로 만들어진 열린 Issue를 검색해 제목까지 같으면 새로 만들지 않고 그 Issue를 결과로 쓴다 |
| GitHub 연결/갱신 | Issue 본문/댓글에서 숨은 표식(`meettoticket:link|update meeting=... work_key=...`)을 찾는다. 있으면 다시 쓰지 않고 성공으로 처리한다. 댓글이 많아 끝까지 확인하지 못하면 `MARKER_CHECK_INCOMPLETE`로 막는다 |
| Notion 신규 | 이미 만들어졌는지 찾아볼 도구가 없다. 결과 불명 뒤 재시도는 중복 페이지 방지를 위해 보내지 않고 `NOTION_VERIFY_REQUIRED`로 실패시킨다. 이 실패는 `outcomeUnknown=false`, `retryable=false`인 일반 `FAILED`로 저장되어 화면의 확인 체크박스가 사라지고, 지난 결과 불명 표시(`previousOutcomeUnknown`)는 실패 때 이어지므로 다시 시도하거나 수정으로 되돌려 재발행해도 같은 실패가 반복된다. 이 티켓을 Notion에 다시 발행할 수 있는 경로가 코드에 없다 (알려진 한계 5절 참고). 사용자는 '회의 후속 티켓' 폴더에서 페이지가 이미 있는지 직접 확인할 수 있을 뿐이다 |
| Slack 신규 | 코드에 재대조가 없다. 결과 불명 뒤 재시도하면 같은 공지가 한 번 더 게시될 수 있다 (재호출하면 메시지가 추가되는 도구). 화면의 확인 체크박스는 재시도 버튼을 켜는 수단일 뿐 중복 게시를 막지 못한다. 게시된 메시지 링크가 비어 있을 수 있어(5절) 사용자가 Slack에서 게시 여부를 확인하기도 어렵다. 즉 완화책이 약하다 |

## 3. 사용자 노출 상태와 내부 상태 대응

### 3.1 정책 목표 상태 4종 (현재 UI는 9종 라벨)

아래 표는 정책 목표의 4종 상태와 내부 `TicketStatus`의 대응이다. 현재 화면은 4종으로 합치지 않고 내부 상태 라벨 9종을 그대로 보여 준다.

| 사용자 노출 상태 | 내부 `TicketStatus` | 비고 |
|---|---|---|
| 검토 대기 | `ASSIGNEE_REQUIRED`, `PENDING_REVIEW`, `CONFIRMED` | 담당 지정 필요, 담당자 검토 중, 확인 후 발행 전. 확인(`CONFIRMED`)은 발행 대기이므로 이 묶음에 둔다 |
| 발행 중 | `PUBLISHING` | 묶음 결과를 기다리는 중. 같은 티켓을 다시 담을 수 없다 |
| 발행됨 | `PUBLISHED`, `LINKED`, `UPDATED` | 신규 생성/기존 항목 연결/기존 항목 갱신. Slack 게시는 에이전트 상태 `POSTED`를 `PUBLISHED`로 저장한다 |
| 실패/확인 필요 | `FAILED` | 3.2의 구분표를 따른다. `outcomeUnknown`만으로 안전한 재시도 여부를 판단하면 안 된다 |
| (4종 밖) | `REJECTED` | 담당자가 거절한 티켓. 발행 대상이 아니므로 4종 상태에 넣지 않고 별도 표시한다 |

### 3.2 FAILED 세부 구분

| `outcomeUnknown` | `retryable` | 의미 | 예 |
|---|---|---|---|
| true | 대개 true | 대상에 반영됐을 수 있음. 확인 후 재시도 | `RESULT_UNKNOWN`, `RESULT_TIMEOUT`, `RESULT_MISSING`(이 둘은 `retryable=false`로 저장됨) |
| false | true | 반영되지 않은 실패로 재시도 가능 | `GITHUB_RATE_LIMIT`, `NOTION_RATE_LIMIT`, `MCP_UNAVAILABLE` |
| false | false | 재시도해도 소용없거나 대상에 이미 있을 수 있음. 안전한 재시도가 아니다 | 권한/설정 오류(`GITHUB_FORBIDDEN`, `NOTION_ROOT_MISSING`), 예외: `NOTION_VERIFY_REQUIRED`와 `MARKER_CHECK_INCOMPLETE`는 대상에 이미 반영됐을 수 있는데도 `outcomeUnknown=false` |

### 3.3 현재 구현과 차이

- 화면은 4종으로 합치지 않고 내부 상태 라벨을 그대로 쓴다 (`TicketCard.tsx`: 담당 지정 필요, 검토 대기, 확인, 거절, 발행 중, 발행됨, 연결됨, 갱신됨, 실패). 4종 표시는 정책 목표이며, 이 문서가 대응 기준이다.
- 결과 화면은 묶음 요약(`Summary`)에서 발행됨/연결됨/갱신됨/실패/발행 중을 따로 센다.
- 묶음(`PublishBatchStatus`)은 `RUNNING`, `SUCCEEDED`, `PARTIAL_FAILED`, `FAILED`이며 티켓 상태와 별개다.

## 4. 결과 불명(UNCERTAIN) UX 정책

결과 불명은 "발행 요청을 보냈지만 성공/실패를 알 수 없는" 상태다.

### 4.1 현재 구현

- 원인: 쓰기 요청을 보낸 뒤 예외가 난 경우(Agent 내부 `UNKNOWN` 상태), Agent 보고에 빠진 티켓, 기한 안에 보고가 오지 않은 묶음.
- 저장: 티켓은 `FAILED` + `outcomeUnknown=true`로 저장된다 (`RESULT_UNKNOWN`, `RESULT_MISSING`, `RESULT_TIMEOUT` 등의 오류 코드).
- 기한 정리: 접수 확인에 실패했거나 Agent가 보고하지 못한 묶음은 기한(`app.publish.result-timeout`, 기본 30분)이 지나면 `PublishBatchExpiry`가 1분(`app.publish.expire-interval-ms`, 기본 60000) 주기로 `RESULT_TIMEOUT` 실패로 끝낸다. 그 전까지 티켓은 발행 중에 머문다.
- 화면: 실패 카드에 "대상 도구에 이미 반영됐을 수 있습니다. 확인 후 다시 시도하세요"를 보여 주고, "반영 여부를 확인했습니다" 체크 후에만 "다시 시도"가 활성화된다. 결과 화면에도 같은 안내가 있다.
- 자동 회수(자동으로 반영 여부를 확인해 상태를 바로잡는 것)와 "발행 확인 중" 표시, 5분 뒤 수동 확인 전환은 코드에 없다. 코드가 하는 재대조는 사용자가 다시 시도할 때 대상별로 돌아간다 (2.3 참고).

### 4.2 정책 목표 (미구현)

| 단계 | 동작 |
|---|---|
| 결과 불명 직후 | 사용자에게 실패로 보이지 않고 "발행 확인 중"으로 표시한다 |
| 자동 회수 | 시스템이 대상(GitHub, Notion, Slack)에서 반영 여부를 먼저 확인해 발행됨 또는 실패로 확정한다 |
| 5분 뒤 | 자동 회수로 확정되지 않으면 "확인 필요"로 바꾸고 사용자가 대상에서 직접 확인한 뒤 재시도하게 한다 |

이 목표를 구현하려면 Notion과 Slack에도 반영 여부 조회 수단이 필요하다 (현재 Notion은 조회 도구가 없고 Slack은 재대조가 없다).

## 5. 알려진 한계와 확인 필요 항목

- Slack 공지 일괄 발행은 backend(`PublishService.publishable`)와 Agent(`batch_publish.py` SLACK 분기)에 구현되어 있다.
- 알려진 결함 (Slack 링크 누락): Agent는 `post_message` 응답의 `url`을 읽지만 MCP 도구는 `permalink`를 돌려준다 (`business-mcp/integrations/slack/api.py`). 실제 연동에서는 `message_url`이 비게 되고, `url`이 없을 때의 가드가 없어 링크 없는 `PUBLISHED`로 저장된다. Agent 테스트는 `url`을 돌려주는 가짜 도구만 쓴다. 별도 이슈로 관리 필요.
- 알려진 한계 (Notion 결과 불명 막다른 경로): 2.3의 Notion 행 참고. 별도 이슈로 관리 필요.
- 결과 화면 링크 허용 도메인은 `frontend/src/batchPublish.ts`의 `safePublishUrl`이 대상별로 검사한다.

## 6. 정책 수치

중복률 기준, 자동 발행 기준 등 정책 수치는 이 문서에 복제하지 않는다. 단일 기준은 이슈 #266 본문의 "결정 기록" 표다 ([이슈 #266](https://github.com/MCP-Capstone/meet-to-ticket/issues/266)). 평가 문서가 저장소에 생기면 그 문서로 링크를 옮긴다.

## 7. 신규 발행 대상 추가 체크리스트

1. `PublishTarget`에 값을 추가하고, backend `PublishService.publishable`에 포함한다.
2. 프로젝트 설정 필드(대상 위치 식별자)와 연결 확인 API를 추가한다.
3. business-mcp에 `integrations/<대상>/`을 만들고 토큰은 `.env`로만 읽는다. 오류는 `[코드] 메시지` 형태로 구분한다.
4. Agent `batch_publish.py`에 분기를 추가하고, 결과 상태/링크 필드를 보고 계약(`PublishItemResult`)과 backend `record`에 매핑한다.
5. 이 문서의 2.1, 2.2, 2.3 표를 채우고, 결과 불명 뒤 중복 방지 방식을 정한다.
6. 결과 화면 링크 허용 도메인(`safePublishUrl`)과 도구 이름 표시(`toolName`)를 추가한다.
7. 필요한 권한과 토큰 이름을 `business-mcp/.env.example`과 README 구성표에 반영한다.
