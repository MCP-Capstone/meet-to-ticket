# API 명세와 Swagger 사용법

#58 문서화 기준: develop의 Spring 공개 API와 #57 반영 후 Agent `/runs` API.
각 서버가 자신의 코드에서 OpenAPI를 생성한다. 경로·입출력 구현을 바꾸지 않고 설명·예시를 추가했다.

| 제공 서버 | 호출자 | Swagger UI | OpenAPI JSON |
|---|---|---|---|
| Spring, 기본 8080 | 프론트 | http://localhost:8080/swagger-ui.html | http://localhost:8080/v3/api-docs |
| Agent, 기본 8001 | Spring | http://localhost:8001/docs | http://localhost:8001/openapi.json |

서버 포트를 바꿨다면 문서 주소도 같은 포트로 바꾼다. Spring 문서에 Agent 경로를 가짜 컨트롤러로 등록하지 않는다.
문서용 예시의 ID·계정·이슈 번호는 계약 설명용이며 실제 발행 결과가 아니다.

## Spring 공개 API

| Method | 경로 | 정상 응답 | 역할 |
|---|---|---|---|
| GET | /api/health | 200 | 서버 상태 |
| POST | /api/auth/signup | 201 | 가입·세션 생성 |
| POST | /api/auth/login | 200 | 로그인·세션 생성 |
| POST | /api/auth/logout | 204 | 세션 제거 |
| GET | /api/auth/me | 200 | 내 정보 |
| GET | /api/projects | 200 | 내 프로젝트: 저장소·도구 연결·멤버 수·최근 회의·관리자 여부와 실행 모드 |
| POST | /api/projects | 201 | 프로젝트 등록 (등록자는 관리자) |
| GET | /api/projects/{projectId} | 200 | 프로젝트 상세, 멤버, 편집 가능 여부 |
| PUT | /api/projects/{projectId} | 200 | 프로젝트 편집 (관리자만) |
| DELETE | /api/projects/{projectId} | 204 | 프로젝트 삭제 (관리자만, 이름 입력 확인) |
| PUT | /api/projects/{projectId}/members/{memberUserId}/role | 200 | 관리자 지정/해제 (관리자만) |
| GET | /api/users?keyword= | 200 | 멤버로 고를 가입 사용자 검색 |
| GET | /api/projects/{projectId}/members | 200 | 팀원·GitHub 계정 |
| GET | /api/tickets/me | 200 | 내 티켓 (담당자별, `projectId` 필터, 상태별 건수) |
| GET | /api/analysis-tasks/{taskId}/tickets | 200 | 전송 결과: 티켓별 발행 대상·받은 사람·상태, 건수 요약, GitHub 이슈 미리보기 (같은 프로젝트 멤버) |
| PUT | /api/tickets/{ticketId}/assignee | 200 | 대표 담당 지정: 담당 지정 필요 티켓을 팀원에게 보냄 (회의록을 올린 사람만) |
| GET | /api/tickets/{ticketId}/preview | 200 | 저장된 최종 발행 본문·상세 백로그 조회 (담당자만, 상태 변경 없음) |
| PATCH | /api/tickets/{ticketId} | 200 | 티켓 카드 수정: 본문, 담당 멤버, 기한 (담당자만, 검토 대기에서만) |
| PATCH | /api/tickets/{ticketId}/publication | 200 | 발행 대상과 처리 유형 변경 (담당자만, 검토 대기에서만) |
| POST | /api/tickets/{ticketId}/confirm | 200 | 확인: 검토 대기 -> 확인 |
| POST | /api/tickets/{ticketId}/reject | 200 | 거절: 검토 대기 -> 거절 |
| POST | /api/tickets/{ticketId}/reopen | 200 | 다시 열기: 확인 -> 검토 대기 |
| POST | /api/tickets/{ticketId}/restore | 200 | 되돌리기: 거절 -> 검토 대기 |
| POST | /api/tickets/publish | 202 | 확인한 N건 발행 (`projectId`·`meetingId` 필터, 회의 지정 시 `ticketIds` 필수). 확인 상태 티켓만 발행 중으로 바꿔 Agent `POST /publish`로 보냄 |
| GET | /api/publish-batches/{batchId} | 200 | 발행 결과: 건수 요약, 티켓별 이슈 링크/실패 사유 (요청한 사용자만) |
| POST | /api/tickets/{ticketId}/retry | 202 | 실패한 티켓 다시 시도. 담당자만(403), 실패 상태가 아니면 409. 결과는 `batchId`로 조회 |
| POST | /api/projects/{projectId}/analysis-tasks | 202 | 회의록 처리 요청 (회의 저장 + 분석 접수, 기획_v16) |
| POST | /api/projects/{projectId}/meetings | 201 | multipart file 업로드·회의 저장 |
| GET | /api/meetings/{meetingId} | 200 | 본문을 제외한 회의 메타데이터 |
| POST | /api/meetings/{meetingId}/analysis-tasks | 202 | 저장된 회의 분석 접수 |
| GET | /api/analysis-tasks/{taskId} | 200 | 상태·오류 조회 |
| GET | /api/analysis-tasks/{taskId}/items | 200 | 종료 항목·집계 조회 |

가입의 `githubName`(GitHub 로그인명)은 선택이다. 미입력이나 공백만 있으면 확인 없이 가입한다.
입력하면 GitHub `GET /users/{login}`으로 사용자 계정인지 확인하고 GitHub 기준 표기로 저장한다. 없는 계정이나 조직 계정은 400 `INVALID_GITHUB_ACCOUNT`이다.
호출 한도(토큰 없이 IP당 시간당 60회)나 장애로 확인하지 못하면 입력값 그대로 저장하고 가입한다.

프로젝트를 등록한 사용자는 관리자(ADMIN)이자 멤버가 되고, 편집(PUT)은 관리자만 한다(아니면 403 `PROJECT_ADMIN_REQUIRED`).
`memberUserIds`는 변경 후 전체 멤버 목록이며, 관리자는 목록에 없어도 남는다. 관리자 지정/해제는 role API(`{"role": "ADMIN" | "MEMBER"}`)로 하고, 마지막 관리자를 해제하면 409 `LAST_PROJECT_ADMIN`이다. 같은 GitHub 저장소는 대소문자가 달라도 409 `DUPLICATE_PROJECT_REPOSITORY`이다.
프로젝트 삭제는 본문 `{"confirmName": "프로젝트 이름"}`이 저장된 이름과 같아야 한다(아니면 400 `PROJECT_NAME_MISMATCH`). 멤버, 회의 기록, 분석 작업과 티켓을 지우고 이미 발행한 GitHub 이슈, Slack 메시지, Notion 페이지는 그대로 둔다. 처리 중인 회의록이 있으면 409 `PROJECT_ANALYSIS_IN_PROGRESS`이다.
저장(POST/PUT)할 때마다 GitHub 계정이 있는 멤버의 저장소 권한을 Agent `POST /projects/check-members`로 확인한다(이전 결과는 먼저 초기화). 멤버별 `repositoryPermission`은 GRANTED/DENIED, 계정이 없으면 NO_ACCOUNT(호출 제외)이다. 멤버 ERROR나 Agent/MCP 전체 실패는 저장을 막지 않고 해당 멤버 `repositoryPermission=null`, `connections.github=ERROR`로 응답한다(전부 성공이면 CONNECTED, 확인할 멤버가 없으면 null). `GET /api/projects/{projectId}`는 외부 호출 없이 저장된 값을 반환한다.
멤버 저장소 권한과 도구 연결은 TASK-006 전까지 확인하지 않는다. GitHub 계정이 없는 멤버만 `NO_ACCOUNT`이고, 확인 전 값은 `null`이다.

로그인·가입·로그아웃·health 외에는 JSESSIONID 세션이 필요하다. Swagger에서 먼저 로그인하면 같은 출처 요청에 쿠키가 적용된다.
Authorize에 쿠키를 직접 넣기보다 login API를 실행한다. 세션 사용자 ID는 입력값으로 노출하지 않는다.
업무 오류는 `{code, message}`. 400(파일/형식/참여자), 401(미로그인), 403(프로젝트 권한),
404(회의/작업 없음), 409(중복 요청/미종료)를 각 API에서 확인한다.
#75부터 분석은 RabbitMQ 큐에 접수하며 Agent 중지 시에도 202를 반환한다. 재시도 후 최종 실패는 상태 조회의 FAILED로 확인한다.

### 담당자별 티켓과 내 티켓 (#166)

Agent 결과를 저장할 때 각 항목의 담당자 이름을 프로젝트 멤버와 연결한다(REQ-010). 단, 발행 대상(`contentJson.publish_target`)이 `SLACK`(회의 결과 공지)이나 `NOTION`인 티켓은 업로더에게 보낸다(업로더가 프로젝트 멤버가 아니면 담당 지정 필요). 멤버 정확히 1명과 연결되면 `PENDING_REVIEW`(검토 대기), 담당자가 없거나 여러 명이거나 멤버와 연결되지 않으면 `ASSIGNEE_REQUIRED`(담당 지정 필요)이다. GitHub 계정이 없는 멤버도 담당자로 연결하며, 이름만 보고 GitHub 로그인명을 추정하지 않는다. 처리 유형은 상태와 별도로 `CREATE`/`LINK`/`UPDATE`이다.
`GET /api/tickets/me`는 로그인 사용자가 담당인 티켓만 반환한다. `projectId`로 거르면 `counts`(`pendingReview`, `confirmed`, `rejected`)도 같은 범위로 센다. 기존 결과 조회 API(`/api/analysis-tasks/{id}/items`)는 바뀌지 않는다.

티켓에는 발행 대상 `publishTarget`(GITHUB, SLACK, NOTION, 기본 GITHUB)과 처리 유형 `processingType`(CREATE, LINK, UPDATE)이 있다. 처리 유형의 첫 값은 Agent 중복 판단의 제안(`contentJson.dedupe.handling.type`)이다.
`PATCH /api/tickets/{ticketId}/publication`으로 담당자가 바꾼다. GitHub 이외 대상은 항상 CREATE이고(연결/갱신을 같이 보내면 400 `PROCESSING_TYPE_TARGET_MISMATCH`), LINK와 UPDATE는 중복 판단이 찾은 열린 기존 이슈가 있을 때만 된다(아니면 400 `EXISTING_ISSUE_REQUIRED`).
티켓 응답의 `dedupe`는 카드 표시용 읽기 전용 값이다: `judged`(false면 "중복 판단 안 됨"), `relation`, `issue`(번호, 제목, 상태, 링크), `overlap`(신규 발행인데 같은 작업이나 관련 이슈가 있으면 "겹칠 수 있음"), `reason`, `before`/`after`(갱신의 변경 전후), `changedByRecheck`.

담당자는 카드에서 본문, 담당 멤버(같은 프로젝트 멤버), 기한을 고치고 확인/거절한다(REQ-014, #167). 수정은 검토 대기 상태에서만 되고 보낸 항목만 바뀐다. 담당 멤버를 바꾸면 그 멤버의 내 티켓으로 옮겨 간다. 기획 4.2 표 밖의 전이와 발행 이후 상태의 수정은 409 `TICKET_STATUS_CONFLICT`, 담당자가 아니면 403 `TICKET_ASSIGNEE_REQUIRED`, 티켓이 없으면 404 `TICKET_NOT_FOUND`이다. 수정한 내용은 `contentJson`의 `description`, `due`, `assignees`에 반영하며, 발행 본문(`issue_body`)은 다시 만들지 않는다.

### 프로젝트 목록 (#214, TASK-008)

`GET /api/projects`는 로그인 사용자가 멤버인 프로젝트만 ID 순으로 반환한다. 미소속이면 `projects: []`, 비로그인이면 401이다. 기존 `projectId`, `name`, 최상위 `executionMode`는 유지한다.

```json
{
  "projects": [{
    "projectId": 1,
    "name": "CSV 내보내기",
    "githubOwner": "MCP-Capstone",
    "githubRepo": "mcp-capstone",
    "connections": {"github": "CONNECTED", "notion": "NOT_CONNECTED", "slack": null},
    "memberCount": 6,
    "lastMeetingDate": "2026-10-23",
    "canEdit": true
  }],
  "executionMode": "MOCK"
}
```

- `connections`: 상세 API와 같은 저장값. `CONNECTED`는 연결됨, `NOT_CONNECTED`는 미연결, `ERROR`는 확인 실패, `null`은 확인 전이다. 목록 화면은 실패·확인 전을 ‘권한 확인 필요’로 안내한다. 조회 중 외부 Agent·도구 호출은 없다.
- `memberCount`: 미가입 팀원을 포함한 전체 프로젝트 멤버 수. `lastMeetingDate`: 저장된 회의 날짜 중 최댓값(회의가 없으면 `null`). 프로젝트 수와 무관하게 소속·멤버 수·최근 회의를 세 번의 조회로 가져온다.
- `canEdit`: 현재 사용자가 프로젝트 관리자이면 `true`. 목록의 편집·삭제 버튼 표시 기준이며, 실제 권한은 기존 PUT·DELETE API가 다시 검사한다.
- 삭제는 기존 `DELETE /api/projects/{projectId}`에 `{"confirmName":"프로젝트 이름"}`을 보내고 성공 시 204를 받는다. 처리 중인 회의가 있으면 409 `PROJECT_ANALYSIS_IN_PROGRESS` 메시지를 표시하고 입력은 유지한다. 삭제 후 현재 업로드 프로젝트도 남아 있는 프로젝트로 변경하며, 모두 삭제하면 선택을 비운다.
- 기존 삭제 API는 회의·작업·티켓·멤버를 삭제하고 이미 발행한 외부 결과물은 유지한다. **기존 이슈 색인 삭제는 TASK-037 후속 작업**이며 현재 삭제 창에도 색인이 남는다고 안내한다.

### 일괄 발행과 발행 결과 (#194)

#### 회의별 발행 확장 (#232)

프론트의 회의별 발행은 `POST /api/tickets/publish?projectId=1&meetingId=12&ticketIds=<UUID1>,<UUID2>`를 사용한다. `ticketIds`에는 확인 창에 표시한 **확인 상태**의 티켓 ID를 넣는다. 로그인 사용자의 해당 회의 티켓을 ID 순으로 잠근 뒤 담당자·프로젝트·검토 상태를 다시 확인한다. 다른 담당자와 다른 회의는 포함하지 않는다.

- `MEETING_REVIEW_INCOMPLETE` (409): 본인의 검토 대기/담당 지정 필요 티켓이 남아 있다.
- `TICKET_STATUS_CONFLICT` (409): 같은 회의의 본인 티켓이 이미 발행 중이다.
- `PUBLISH_SELECTION_CHANGED` (409): 확인 창의 ID와 현재 확인 상태 티켓이 다르거나 `ticketIds`를 생략했다. 목록을 다시 불러오고 확인 창부터 진행한다.
- `PUBLISH_TARGET_UNSUPPORTED` (409): 일괄 발행이 처리하지 않는 도구의 확인 티켓이 포함돼 있다. 일부만 몰래 제외하지 않는다. 현재 GitHub, Notion, Slack은 모두 처리한다.
- `NO_CONFIRMED_TICKETS` (409): 발행할 본인 티켓이 없거나 프로젝트 필터와 일치하지 않는다.

응답과 결과 조회 계약은 기존과 같다. 회의 필터를 생략한 기존 호출은 아래의 기존 동작을 유지하며, 새 화면에서는 반드시 회의 필터를 보낸다.


`POST /api/tickets/publish`는 로그인 사용자가 담당인 확인(`CONFIRMED`) 티켓만 한 묶음으로 담는다. 검토 대기와 거절 티켓은 담기지 않는다. GitHub 이슈, Notion 페이지, Slack 회의 결과 공지 티켓을 모두 담는다. 담긴 티켓은 `PUBLISHING`(발행 중)이 되어 다시 담을 수 없고(중복 클릭 방지, 없으면 409 `NO_CONFIRMED_TICKETS`), Agent `POST /publish`가 202로 접수한다. 202는 접수이지 발행 성공이 아니다. Agent가 접수하지 않은 것이 확실하면 티켓은 확인 상태로 돌아가고 502 `AGENT_ACCEPTANCE_FAILED`이다.
Agent는 결과를 `POST /internal/publish-batches/{batchId}/result`(내부 토큰)로 보고하고, 백엔드는 티켓 상태를 `PUBLISHED`/`LINKED`/`UPDATED`/`FAILED`로 바꿔 이슈 번호/URL/실패 사유를 저장한다. Slack 공지는 Agent가 `POSTED`와 `messageUrl`로 알려 주며 `PUBLISHED`로 저장한다. 결과 항목의 `githubIssueUrl`에는 `publishTarget`에 따라 GitHub 이슈, Notion 페이지, Slack 메시지 URL이 담긴다. 발행 직전 재대조로 처리 유형이 바뀌면 실제 실행한 유형을 저장한다. 요청은 갔지만 결과를 모르는 경우(`UNKNOWN`)와 보고에서 빠진 티켓은 `FAILED`이면서 `outcomeUnknown=true`이다. 다시 시도하면 이슈가 두 번 생길 수 있어 구분한다. 같은 묶음의 보고가 다시 와도 한 번만 반영한다.
`GET /api/publish-batches/{batchId}`는 `RUNNING`(결과 대기)이면 계속 조회한다. `GET /api/tickets/me`의 `counts.failed`는 실패 배너용이다. MOCK 모드는 GitHub 발행 없이 즉시 `PUBLISHED`로 저장하며 이슈 링크가 없다.
`POST /api/tickets/{ticketId}/retry`는 실패(`FAILED`) 티켓 하나를 새 묶음으로 다시 발행한다(REQ-019). 티켓 행을 잠근 뒤 발행 중으로 바꾸므로 같은 티켓을 동시에 다시 시도해도 한 번만 접수되고 나머지는 409 `TICKET_STATUS_CONFLICT`이다. 결과는 같은 규칙으로 저장되어 성공하면 `PUBLISHED`/`LINKED`/`UPDATED`, 실패하면 다시 `FAILED`(새 사유)가 된다. Agent가 접수하지 않은 것이 확실하면 502 `AGENT_ACCEPTANCE_FAILED`이고 티켓은 이전 실패 상태와 사유를 유지한다. 결과 항목의 `failureCategory`는 `REPOSITORY_PERMISSION`(`REPOSITORY_NOT_ALLOWED`, `GITHUB_AUTH`, `GITHUB_FORBIDDEN`)이면 PM이 프로젝트 설정에서 저장소 권한을 풀어야 하는 실패이고, 그 밖은 `OTHER`이다. `outcomeUnknown=true`인 티켓도 재시도할 수 있으며 Agent가 발행 직전 재대조로 이미 만들어진 이슈를 연결로 바꾼다.
`GET /api/tickets/me`의 티켓은 실패(`FAILED`) 상태일 때만 `publishFailure`(`errorCode`, `errorMessage`, `failureCategory`, `retryable`, `outcomeUnknown`)를 담고 그 밖에는 null이다. `outcomeUnknown`에는 이전 시도의 결과 모름도 포함되며, 화면은 이때 GitHub 확인 단계를 거친 뒤 다시 시도하게 한다.
`POST /api/tickets/{ticketId}/revise`는 실패(`FAILED`) 티켓을 검토 대기(`PENDING_REVIEW`)로 되돌려 고친 뒤 다시 확인할 수 있게 한다. 담당자만 할 수 있고 실패 상태가 아니면 409 `TICKET_STATUS_CONFLICT`이다. 티켓의 지난 발행 결과(오류 코드, 재시도 가능 여부, 결과 모름, 이슈 번호/URL)는 비운다. 지난 묶음의 `GET /api/publish-batches/{batchId}`는 묶음이 끝날 때 저장한 티켓별 기록을 보여 주므로, 이후 티켓을 다시 시도하거나 수정으로 되돌려도 바뀌지 않는다.
지난 발행이 결과 모름(`outcomeUnknown=true`)이었던 티켓은 수정을 거쳐도 표시가 남고, 다시 발행할 때 Agent에 `previousOutcomeUnknown=true`로 보낸다. Agent는 담당자가 처리 유형을 직접 바꿨더라도 발행 직전 재대조를 하고, 이미 반영된 것이 있으면 다시 쓰지 않는다. 신규 발행은 같은 `work_key`의 열린 이슈(제목 일치)를 그 티켓의 결과(`PUBLISHED`)로 보고, 연결은 같은 회의의 연결 댓글이, 갱신은 같은 회의의 `회의 변경 사항` 섹션이 이미 있으면 다시 달거나 덧붙이지 않는다. 댓글이 너무 많아 끝까지 확인하지 못하면 쓰지 않고 `MARKER_CHECK_INCOMPLETE`로 실패시킨다. 확실한 성공이 오면 표시는 풀린다.

### 전송 결과와 대표 담당 지정 (#213)

회의록 처리가 끝나면 업로더는 전송 결과를 본다 (REQ-012, UI 플로우 6쪽).

- `GET /api/analysis-tasks/{taskId}/tickets`: 같은 프로젝트 멤버면 볼 수 있다 (아니면 403). 처리 중이면 409 `TASK_NOT_FINISHED`
  - `counts`: `total`, `pendingReview`(기존 이슈 연결 제외), `linked`(처리 유형 LINK), `assigneeRequired`
  - `tickets[]`: `ticket`(내 티켓 조회와 같은 형식, `assignee`가 받은 사람), `mine`(받은 사람이 나면 true, 화면에 "나"), `preview`(GitHub 티켓만: 이슈 본문 Markdown, 라벨, 근거)
  - `canAssign`과 `members`: 회의록을 올린 사람이면 대표 담당 후보를 함께 준다
  - 미리보기 본문에는 회의록 원문이 섞여 있다. HTML로 그릴 때는 sanitize한다 (프론트 `IssueMarkdown`은 HTML을 넣지 않고 React 요소로만 그린다)
- `PUT /api/tickets/{ticketId}/assignee` `{"memberId": 11}`: 담당 지정 필요(`ASSIGNEE_REQUIRED`) 티켓에 같은 프로젝트 팀원을 대표 담당으로 지정하면 검토 대기가 되어 그 팀원의 내 티켓으로 간다 (REQ-011). 회의록을 올린 사람만 할 수 있다 (아니면 403 `TICKET_UPLOADER_REQUIRED`). 담당 지정 필요가 아니면 409 `TICKET_STATUS_CONFLICT`, 같은 프로젝트 팀원이 아니면 404 `PROJECT_MEMBER_NOT_FOUND`

### 회의록 처리 요청 (#153)

`POST /api/projects/{projectId}/analysis-tasks`는 회의 저장과 분석 접수를 한 번에 한다 (기획_v16 REQ-006). 업로드 화면은 이 API를 쓴다. 기존 업로드(`POST /api/projects/{projectId}/meetings`)와 분석 시작(`POST /api/meetings/{meetingId}/analysis-tasks`)은 프론트가 옮길 때까지 유지한다.

요청은 `multipart/form-data`다.

| 필드 | 필수 | 내용 |
|---|---|---|
| `meetingDate` | O | 회의 날짜 `YYYY-MM-DD`. 상대 기한 계산 기준 |
| `content` | 둘 중 하나 | 붙여넣은 회의록 텍스트 |
| `file` | 둘 중 하나 | 회의록 파일. UTF-8 `.md` 또는 `.txt` |
| `title` | | 회의 제목. 비우면 회의록 첫 `#`·`##` 제목줄, 파일 이름, `<회의 날짜> 회의` 순 |

- 참석자 줄과 멤버 GitHub 계정은 요구하지 않는다. 담당자는 추출 단계에서 멤버와 연결하고, 계정이 없는 멤버는 이슈에 이름만 적는다 (REQ-010).
- 응답은 기존 분석 시작과 같은 `{taskId, meetingId, status: "QUEUED", executionMode}`다. 이후 `GET /api/analysis-tasks/{taskId}`로 조회한다.
- 날짜 누락·형식 오류, 회의록 없음, `content`와 `file` 동시 입력, 확장자·인코딩 오류는 400 `INVALID_MEETING_INPUT`이다. 이때 회의는 저장되지 않는다.

```bash
# 붙여넣기
curl -b cookie.txt -F meetingDate=2026-10-22 -F 'content=# CSV 점검
박서연이 내일까지 API를 수정한다.' http://localhost:8080/api/projects/1/analysis-tasks
# md 파일
curl -b cookie.txt -F meetingDate=2026-10-22 -F file=@회의록.md http://localhost:8080/api/projects/1/analysis-tasks
```

### 외부 AI·GitHub 없이 테스트

1. 루트에서 `docker compose up -d postgres rabbitmq`로 DB와 큐를 준비하고 backend에서 `./gradlew bootRun --args='--spring.profiles.active=demo'` 실행.
2. Swagger 접속 후 `POST /api/auth/login`에 demo 계정 입력. 기본 demo1 / password1은 개발용이며 환경변수로 바뀔 수 있다.
3. `GET /api/projects`로 프로젝트 ID, `GET /api/projects/{projectId}/members`로 실제 팀원 확인.
4. TXT 파일에 제목·날짜·참여자를 준비하고 업로드 실행. 예시:

   ```text
   # CSV 중간 점검
   회의 날짜: 2026-10-22
   참여자: 박종민, 허민규
   박종민이 내일 오후 5시까지 CSV 빈 결과 오류를 수정한다.
   ```

   참여자는 선택한 프로젝트 등록 이름과 정확히 일치해야 한다. 원본의 여러 줄 참석자·역할 설명은 현재 미지원.
5. 반환된 meetingId로 분석 POST 실행. 본문은 없으며 202의 taskId를 복사.
6. 상태 GET을 반복하고 COMPLETED / PARTIALLY_FAILED / FAILED에서 중단.
7. items GET으로 항목·집계 확인. 처리 중 items GET은 409.

기본 draft 시나리오는 고정 항목 1건의 발행 전 초안(`reviewStatus: PENDING`, `publishStatus: DRAFT`)이며 `summary.drafts`가 1이다.
서버를 재시작할 때 `AGENT_MOCK_SCENARIO=approved|empty|rejected|failed|partial` 중 하나로 결과 분기를 확인할 수 있다. `approved`는 Phase 1 흐름(고정 항목 1건 SKIPPED)이다.

상태 GET 응답의 `progress`는 진행 화면용 세부 정보다 (아직 없으면 `null`). `step`은 `CONTEXT`(맥락 파악) → `EXTRACTING`(티켓 추출) → `DEDUPING`(중복 검수) → `DISPATCHING`(담당자에게 전송)이고, `context.{members, issues, notionDocs}`, `extraction.{todos, decisions, openQuestions}`, `dedupe.{done, total, linked}`가 단계마다 쌓인다. 아직 모르는 값은 `null`이다. `stage`는 기존 값 그대로다.
MOCK 메모리 작업은 재시작하면 사라져 진행 중 작업이 실패할 수 있다. DB 종료 결과는 유지된다.

## Agent 내부 API — 현재 구현

Agent 요청의 `project.notionPageUrl`에는 프로젝트의 Notion 기획 페이지를 담는다 (TASK-019). Notion을 연결하지 않았거나 연결 오류가 확인된 프로젝트는 `null`이고, Agent는 Notion 맥락 없이 처리한다.

| Method | 경로 | 응답 |
|---|---|---|
| POST | /runs | 기본 202 `{taskId, status: ACCEPTED}` |
| POST | /publish | 202 `{batchId, status: ACCEPTED}`. 확인된 티켓 일괄 발행 |
| POST | /runs?wait=true | 로컬 테스트용 200 최종 결과 |
| GET | /runs/{task_id} | 200 진행·종료 결과, 알 수 없는 작업은 404 |
| GET | /runs/{task_id}/history | 200 노드별 실행 기록(checkpoint, 시간 순서), 재시작하면 사라짐, 알 수 없는 작업은 404 |
| GET | /health | 200 설정·상태, 외부 연결 성공 검증은 아님 |

Agent 환경에서 `uvicorn main:app --port 8001`로 실행한다. 설치는 [Agent 안내](../../agent/README.md)를 따른다.
문서 조회만으로 LLM·MCP를 호출하지 않는다. POST는 실제 업무를 실행하므로 단독 테스트는
`agent/.env`의 `DRY_RUN=true`, `BACKEND_BASE_URL=` 설정으로 발행·콜백을 끈다. 설정 로더는 .env를 환경변수보다 우선한다.

`POST /publish`는 백엔드가 `batchId`와 `reviewStatus: CONFIRMED` 티켓만 보내는 내부 API다. GitHub 티켓에는 프로젝트 저장소와 확정한 `processingType`(`CREATE`/`LINK`/`UPDATE`), 기존 이슈 번호(연결·갱신 때), 편집된 `contentJson`을 포함한다. Slack 공지 티켓에는 `publishTarget: SLACK`, 프로젝트 `slackChannelId`, 공지의 `contentJson.slack.text`·`reviewers`, 연결된 `slackMembers[{name, slack_user_id}]`(Agent가 dict로 읽어 키가 snake_case)를 함께 보낸다. Agent는 GitHub 티켓을 저장소별 재대조하고, Slack 공지는 한 번 `post_message`로 게시한다. 최종 결과는 `POST /internal/publish-batches/{batchId}/result`에 GitHub의 `PUBLISHED`/`LINKED`/`UPDATED`, Slack의 `POSTED`, 실패·결과 모름과 이슈·댓글·메시지 링크를 함께 보고한다. 같은 `batchId` 재요청은 중복 발행하지 않는다.
DRY_RUN도 실제 추출·검수 LLM 호출과 모델 API 키가 필요하다. 키는 Swagger 요청 JSON에 넣지 않는다.
현재 Agent dry-run은 승인 항목을 SKIPPED(발행 생략)로 반환한다. 이를 실제 GitHub 생성 거절로 해석하지 않는다.
가짜 LLM·MCP로 확인하려면 Agent의 자동 테스트를 실행한다.

현재 Agent API는 내부 인증을 강제하지 않으며 `X-Internal-Api-Key` 보안 스키마를 가짜로 표시하지 않는다.
Spring의 `AGENT_INTERNAL_API_KEY`는 선택 설정이며 지정하면 헤더로 전송한다. Agent의 실제 키 검증은 아직 없다.
Agent HTTP 오류는 `{detail}`이고 요청 검증은 422다. 실행 실패는 HTTP 200의 FAILED일 수 있다.
RUNNING만 중복 409로 막으며 종료된 taskId POST는 재실행될 수 있으므로 Spring처럼 접수 후 GET만 사용한다.
실행 상태는 메모리 저장이며 재시작·멀티워커 간 공유를 보장하지 않는다.

## 이전 MD 설계와 현재 구현의 대응

기존 `Spring_Agent_내부_API_명세서.md` v0.2는 설계 기준이다. 최신 develop은 #57의 변환 계층을 사용한다.

| 항목 | 이전 MD v0.2 | 현재 Agent → Spring |
|---|---|---|
| 접수 | POST /internal/v1/analysis-tasks | POST /runs → Spring QUEUED |
| 조회 | GET /internal/v1/analysis-tasks/{taskId} | GET /runs/{task_id} |
| 종료 상태 | COMPLETED/PARTIALLY_FAILED/FAILED | SUCCEEDED/FAILED → 항목 결과로 Spring 상태 정규화 |
| 진행 단계 | 단계별 응답 | 보통 RUNNING만 반환. Spring은 기본 EXTRACTING으로 표시 |
| 항목 ID | itemId | taskItemId → itemId |
| 집계 | succeeded 등 | published, linked, needsCheck 등. Spring은 항목에서 summary 재계산 |
| 검수 | APPROVED/REJECTED | PENDING/NEEDS_CHECK도 가능. 승인 외는 Spring REJECTED/BLOCKED |
| 오류 | {error:{code,message,taskId}}, 400/401 | {detail}, 422/404/409. 내부 인증 검증은 현재 없음 |
| 중복 ID | 종료 여부 무관 409 | RUNNING인 ID만 409 |
| 콜백 | 사용 안 함 | Agent가 `BACKEND_BASE_URL`을 설정하면 `/internal/tasks/{id}/progress`로 진행 단계를 보고하고 Spring이 단계를 갱신한다. `/result`는 202만 반환하며 결과는 폴링으로 받는다 |

이전 내부 경로·키 검증·MD의 모든 검수 정책이 구현됐다고 의미하지 않는다. 차이를 해소하는 기능 수정은 별도 작업이다.

## 검증과 명세 내려받기

```bash
# backend/
./gradlew test bootJar

# agent/ (가상환경 활성화 후)
python -m pytest -q

# 서버 실행 후 선택적으로 JSON 저장
curl --fail http://localhost:8080/v3/api-docs -o /tmp/spring-openapi.json
curl --fail http://localhost:8001/openapi.json -o /tmp/agent-openapi.json
```

Spring 테스트는 생성된 스키마·인증·업로드·상태 코드·예시를 검증한다.
Agent 테스트는 실제 경로·스키마·200/202/404/409/422와 가짜 의존성 실행을 확인한다. 실제 GitHub에 생성하지 않는다.
문서가 코드에서 생성되므로 내려받은 JSON을 수동 편집해 별도 원본으로 유지하지 않는다.

### 내 티켓 최종 발행 본문·상세 백로그 조회 (#245)

`GET /api/tickets/{ticketId}/preview` → 200. 로그인한 현재 담당자·프로젝트 멤버만 가능하다.

```json
{
  "ticketId": "uuid", "title": "저장된 제목", "status": "PENDING_REVIEW",
  "publishTarget": "GITHUB", "executionMode": "LIVE",
  "publication": {
    "kind": "CREATE", "body": "## 작업 내용\n저장된 설명",
    "secondaryBody": null, "labels": [], "githubAssignees": [], "targetIssueNumber": null
  },
  "backlog": {
    "description": "저장된 설명", "tasks": ["상세 작업"],
    "acceptanceCriteria": ["완료 조건"], "evidenceQuotes": ["회의록 근거"], "openQuestions": []
  }
}
```

`kind`는 CREATE(이슈 본문), LINK(댓글), UPDATE(기존 본문에 추가할 섹션)이다. UPDATE의 `secondaryBody`는 알림 댓글이다. 현재 발행과 같은 저장값 변환·본문 생성 함수를 사용하며, 숨은 중복 발행 확인 표식도 반환될 수 있다. 발행 직전 재대조로 유형·대상이 달라질 수 있다. 과거 발행 원문이 아니라 현재 저장값의 미리보기이다. GitHub 이외에는 `publication: null`, 백로그는 그대로 반환한다. 미제공된 상세 배열은 빈 배열이다. MOCK 문안은 실제 문안과 다를 수 있다.

조회로 승인·발행·내용 수정은 일어나지 않는다. 오류: 401(미로그인), 403 `TICKET_ASSIGNEE_REQUIRED` 또는 `PROJECT_ACCESS_DENIED`, 404 `TICKET_NOT_FOUND`, 502 `TICKET_PREVIEW_UNAVAILABLE`(Agent 조회 실패).
