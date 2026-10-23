# frontend

React + TypeScript + Vite

## 실행

```bash
nvm use        # .nvmrc의 Node 24 사용 (nvm이 없으면 Node 24 LTS 설치)
npm ci         # package-lock.json 그대로 설치
npm run dev
```

http://localhost:5173 에서 열린다.

## Node / 패키지 관리

| 항목 | 규칙 |
|---|---|
| Node | **24 LTS** 권장 (`.nvmrc`). 최소 `20.19` 또는 `22.12` 이상 (Vite 8 요구) |
| 패키지 매니저 | **npm만** 쓴다. yarn / pnpm을 쓰면 lock 파일이 따로 생긴다 |
| 버전 확인 | `node -v`, `npm -v` |

- Node 버전이 맞지 않으면 `npm ci` / `npm install`이 `EBADENGINE` 에러로 멈춘다. (`.npmrc`의 `engine-strict`)
- `node_modules/`는 커밋하지 않는다.

### 언제 무엇을 실행하나

| 상황 | 명령 |
|---|---|
| 처음 clone 했을 때 | `npm ci` |
| pull 받았는데 `package.json` / `package-lock.json`이 바뀌었을 때 | `npm ci` |
| 패키지를 추가할 때 | `npm install <패키지>` (개발용은 `npm install -D <패키지>`) |
| 설치가 꼬였을 때 | `rm -rf node_modules && npm ci` |

- `npm ci`는 lock 파일에 적힌 버전 그대로 설치한다. 6명이 같은 버전을 쓰도록 평소에는 `npm ci`를 쓴다.
- 패키지를 추가 / 삭제 / 업데이트하면 **`package.json`과 `package-lock.json`을 함께 커밋**한다.
- 패키지 업데이트(`npm update`, 메이저 버전 올리기)는 혼자 하지 않고 이슈로 먼저 공유한다. 새 버전 확인은 `npm outdated`.
- 패키지를 바꾼 PR은 본문 참고 사항에 **"패키지 업데이트 필요: `cd frontend && npm ci`"**를 적는다. (PR 템플릿 체크리스트)

## 스크립트

| 명령 | 설명 |
|---|---|
| `npm run dev` | 개발 서버 실행 (5173) |
| `npm run build` | 타입 검사 후 빌드 |
| `npm run lint` | 린트 (oxlint) |
| `npm run test:progress` | 진행 단계·미확인 건수·종료 상태 판단 테스트 (추가 의존성 없음) |
| `npm run preview` | 빌드 결과 미리보기 |

## mock 모드

백엔드 없이 화면을 개발할 수 있도록 기본값은 가짜 API(mock)를 사용한다. 화면 제목 옆에 `MOCK` 표시가 나온다.

- 데모 계정 `demo1` / `password1`로 바로 로그인할 수 있다. (백엔드 demo 프로필과 같은 계정)
- mock에서 가입한 계정과 로그인 상태는 브라우저 탭을 닫기 전까지(sessionStorage) 유지된다.

백엔드가 `executionMode=MOCK`(demo 프로필)이면 헤더에 "모의 실행", 결과 화면에 "실제 GitHub 이슈는 만들지 않았습니다" 안내가 나온다.

실제 백엔드에 연결하려면 `.env.example`을 `.env.local`로 복사하고 값을 바꾼 뒤 개발 서버를 다시 실행한다.

```
VITE_USE_MOCK=false
```

## 구조

```
src/
├── api/
│   ├── types.ts      # API 데이터 형식 (백엔드와 필드 이름 맞춤)
│   ├── client.ts     # fetch 공통 처리, 에러 변환
│   ├── real.ts       # 실제 백엔드 호출
│   └── index.ts      # mock / real 선택
├── mocks/            # 가짜 API와 샘플 데이터 (샘플 .txt 회의록 포함)
├── hooks/
│   └── useAnalysisPolling.ts  # 분석 상태 2초 간격 조회
├── components/       # AuthForm, UploadForm, AnalysisProgress, ResultList, Stepper, Spinner
└── App.tsx           # 로그인 확인 → 업로드 → 분석 → 결과 확인 흐름
```

## 사용하는 API

| Method | URL | 설명 |
|---|---|---|
| POST | `/api/auth/signup` | 회원가입 (가입 후 바로 로그인) |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/auth/me` | 로그인 상태 확인 (새로고침 시) |
| GET | `/api/projects` | 내 프로젝트 목록 + 백엔드 실행 모드(`MOCK` / `LIVE`). 업로드 화면에서 선택한다 |
| GET | `/api/projects/{projectId}/members` | 팀원 목록. 참여자 ID를 이름으로 표시 |
| POST | `/api/projects/{projectId}/analysis-tasks` | 회의 저장·처리 접수 (multipart, `meetingDate`와 `file` 또는 `content`) |
| GET | `/api/meetings/{meetingId}` | 접수한 회의 메타데이터 조회 |
| POST | `/api/projects/{projectId}/meetings` | 기존 TXT 업로드 API (새 화면에서는 호출하지 않음) |
| POST | `/api/meetings/{meetingId}/analysis-tasks` | 기존 분석 시작 API (새 화면에서는 호출하지 않음) |
| GET | `/api/analysis-tasks/{taskId}` | 분석 상태 / 단계 (2초 간격 조회) |
| GET | `/api/analysis-tasks/{taskId}/items` | 항목별 검수 / 발행 결과 + 집계 (작업이 끝난 뒤) |

자세한 요청 / 응답은 백엔드 Swagger(http://localhost:8080/swagger-ui.html)와 `backend/README.md` 참고.

| 값 | 의미 |
|---|---|
| 작업 상태 | `QUEUED` 대기 / `RUNNING` 진행 / `COMPLETED` 완료 / `PARTIALLY_FAILED` 일부 발행 실패 / `FAILED` 실패 |
| 단계 | `EXTRACTING` 추출 / `NORMALIZING` 담당자 / 기한 정리 / `REVIEWING` 검수 / `PUBLISHING` 발행 / `VERIFYING` 확인 |
| 발행 상태 | `SUCCEEDED` 발행됨 / `FAILED` 실패 / `UNKNOWN` 확인 필요 / `SKIPPED` 생략(모의 실행) / `BLOCKED` 검수 반려로 제외 |

## 회원가입 GitHub 계정 입력 (TASK-002 / #41)

- 가입 화면에서 GitHub 로그인명을 선택 입력한다. 프로필 표시 이름이나 URL은 입력하지 않는다.
- `POST /api/auth/signup` 요청은 기존 `loginId`, `password`, `name`에 `githubName`을 추가한다.
- `githubName`은 앞뒤 공백을 제거한 문자열이며, 미입력 또는 공백만 입력한 경우 `null`이다.
- 계정 존재 확인은 TASK-001의 백엔드에서 수행한다. HTTP 400과 오류 코드 `INVALID_GITHUB_ACCOUNT`를 반환하면 GitHub 입력란에 오류를 표시하고 수정 후 재요청할 수 있다.
- 중복 아이디 등 나머지 오류는 기존 공통 안내를 유지한다. 로그인 요청에는 GitHub 계정을 추가하지 않는다.
- 이 API 계약은 TASK-001 담당자에게 전달할 프론트엔드 기준이다. 실제 계정 검증·저장은 백엔드 반영 후 통합 확인해야 한다. 현재 백엔드에는 해당 필드와 검증이 없다.
- mock 가입은 입력값을 저장하지만 실제 GitHub 계정 존재 여부를 확인하지 않는다.

TASK-001 전달 사항:

> TASK-002는 `githubName: string | null` 기준으로 구현했습니다. 미입력은 `null`, 입력 시 앞뒤 공백을 제거합니다. 계정 존재 확인은 가입 API에서 처리하고 유효하지 않은 계정은 HTTP 400 / `INVALID_GITHUB_ACCOUNT`로 반환해 주세요. 변경이 필요하면 알려주세요.

## 프로젝트 등록·편집 (TASK-007 / #131)

로그인 후 **프로젝트** 목록에서 **편집** 또는 **새 프로젝트**를 고른다. 편집 화면의 프로젝트 선택에서도 기존 프로젝트와 새 프로젝트 등록을 선택할 수 있다. 소속 프로젝트가 없는 사용자도 새 프로젝트를 만들 수 있다.

- 기본 정보: 이름(필수), 설명(선택)
- 도구 연결 정보: GitHub `소유자/저장소`(필수), Notion 기획 페이지 URL·Slack 채널 ID(선택)
- 멤버: 가입한 사용자를 이름·GitHub 로그인명으로 검색해 추가·제거한다. 동명이인은 GitHub 로그인명으로 구분한다. GitHub 계정과 저장소 권한은 읽기 전용이다.
- 권한: 상세 응답의 `canEdit=false`이면 읽기 전용으로 표시한다. 저장 API의 403도 안내한다. 실제 권한 검사는 서버가 담당한다.
- 저장: 필수값·저장소 형식을 검사하고 저장 중 중복 요청을 막는다. 실패하면 입력을 유지한다. GitHub 저장소를 바꾸거나 멤버를 추가하면 기존 권한을 새 권한처럼 표시하지 않고 저장 후 확인하도록 안내한다.
- 기존 분석 진행 중에는 프로젝트 설정 이동을 막아 진행 화면의 상태를 유지한다.

### TASK-005·006 연동 계약

프로젝트 목록·삭제 화면은 아래 TASK-008 설명을 참고한다.

프로젝트 조회·저장·가입 사용자 검색 API는 #159 / PR #161에 반영됐다. 관리자 역할 변경 API는 #165 / PR #169에 반영됐다. 실제 외부 도구 연결·멤버 권한 확인은 별도 담당 기능의 응답을 따른다.

| 메서드 | 경로 | 응답 |
|---|---|---|
| POST | `/api/projects` | 201, `ProjectDetail` |
| GET | `/api/projects/{projectId}` | 200, `ProjectDetail` |
| PUT | `/api/projects/{projectId}` | 200, `ProjectDetail` |
| GET | `/api/users?keyword={검색어}` | 200, `{ users: [{ userId, name, githubName }] }` |
| PUT | `/api/projects/{projectId}/members/{memberUserId}/role` | 200, `ProjectDetail` |

등록·수정 요청 (`memberUserIds`는 변경 후 전체 멤버 목록):

```json
{
  "name": "CSV 내보내기",
  "description": "관리자 사용자 목록 내보내기",
  "githubOwner": "MCP-Capstone",
  "githubRepo": "mcp-capstone",
  "notionPageUrl": null,
  "slackChannelId": null,
  "memberUserIds": [1, 2]
}
```

`ProjectDetail` 응답:

```json
{
  "projectId": 1,
  "name": "CSV 내보내기",
  "description": "관리자 사용자 목록 내보내기",
  "githubOwner": "MCP-Capstone",
  "githubRepo": "mcp-capstone",
  "notionPageUrl": null,
  "slackChannelId": null,
  "members": [{ "userId": 1, "name": "함주헌", "githubName": "juhun-ham", "admin": true, "repositoryPermission": null }],
  "connections": { "github": null, "notion": "NOT_CONNECTED", "slack": "NOT_CONNECTED" },
  "canEdit": true
}
```

- 권한 값: `GRANTED` / `DENIED` / `NO_ACCOUNT` / `null`. GitHub 계정이 없는 멤버만 `NO_ACCOUNT`이며, 미확인 또는 확인 실패는 `null`이다.
- 연결 값: `CONNECTED` / `NOT_CONNECTED` / `ERROR`. 모든 연결 값은 확인 전 또는 확인 실패 시 `null`도 허용한다.
- `repositoryPermission`과 `connections.github`의 `null`은 화면에 ‘확인되지 않음’으로 표시한다. TASK-006 / #139의 확인 실패도 같은 값이므로 미확인과 실패를 구분하지 않고, ‘권한 없음’이나 ‘미연결’로 해석하지 않는다.
- 저장 전 저장소 변경이나 멤버 추가는 ‘저장 후 확인’으로 표시하며, 저장 응답이 `null`이면 ‘확인되지 않음’으로 표시한다. 그 외 API 오류는 `{ code, message }` 오류 안내를 유지한다.
- 검색어가 빈 문자열이면 선택 가능한 가입 사용자 목록을 반환한다. 멤버 GitHub 계정은 사용자 정보에서 가져오며 저장 요청에는 포함하지 않는다.
- 로그인·관리자 확인, 등록자 자동 관리자 지정, 연결 계정 인증과 실제 외부 권한 확인은 백엔드 담당 범위다. 인증 토큰을 화면에서 입력·보관하지 않는다.

### mock에서 확인하기

`VITE_USE_MOCK=true npm run dev`로 실행하고 `demo1 / password1`로 로그인한다. mock 프로젝트와 멤버 변경은 브라우저 탭의 sessionStorage에 남아 새로고침 후에도 다시 편집할 수 있다. 탭을 닫으면 초기화되며 실제 DB에는 저장하지 않는다.

- 초기 사용자 6명은 화면 개발용 가입 사용자다. `member2` ~ `member6` / `password1`로도 로그인할 수 있으며 기본 프로젝트 편집은 `demo1`만 가능하다.
- 초기 멤버 중 GitHub 계정이 있는 멤버의 권한은 `null`, 허민규는 `NO_ACCOUNT` 표시 예시다. 실제 팀원의 권한·계정 상태를 뜻하지 않는다.
- 신규 가입한 mock 사용자도 멤버 검색 목록에 포함된다. 새 프로젝트 등록자는 자동으로 관리자·멤버가 된다.
- mock의 GitHub 연결 상태와 계정이 있는 멤버의 권한은 조회·저장 시 `null`로 반환한다. Notion·Slack 연결 상태는 고정 모의 결과이며 외부 서비스에 실제 연결하거나 검증하지 않는다.
- 실제 API를 확인할 때는 `VITE_USE_MOCK=false`로 실행한다. 실 API 실패 시 mock으로 자동 대체하거나 저장 성공을 표시하지 않는다.

## 회의록 업로드·처리 요청 (TASK-010 / #163)

업로드 화면에서 프로젝트·회의 날짜를 선택하고 **파일 업로드** 또는 **텍스트 붙여넣기**를 고른 뒤 **티켓 추출**을 누른다. 회의록 안의 날짜·참석자 줄은 필수가 아니며, 상대 기한 기준은 화면에서 선택한 날짜다. 날짜를 현재 날짜로 자동 대체하지 않는다.

- 파일: UTF-8 `.md`·`.txt`(대소문자 무관), 기본 10MB 이하. 빈 파일·공백뿐인 내용·NUL·잘못된 UTF-8을 사전 검사한다. 파일을 선택하거나 끌어놓는 것만으로는 서버에 요청하지 않는다.
- 붙여넣기: 빈 내용·NUL과 UTF-8 기준 10MB 초과를 사전 검사한다. 유효한 원문은 공백·줄바꿈을 유지해 전송한다.
- 실제 서버 한도는 `MAX_UPLOAD_SIZE` 설정을 따른다. multipart 부가 데이터도 요청 크기에 포함되므로 서버의 크기 오류 응답도 표시한다.
- 두 입력 탭의 내용은 전환 시 유지하지만 선택한 방식의 `file` 또는 `content` 하나만 전송한다.
- 요약: 프로젝트 상세 응답의 저장소·멤버 수·Slack 채널·연결 상태를 표시한다. `existingIssueCount`, `notionDocumentCount`는 선택 메타데이터이며 제공되지 않으면 ‘확인되지 않음’이다. Notion 문서 수는 연결이 확인된 경우만 표시한다. 현재 프로젝트 API에는 두 건수가 없어 임의의 0을 표시하지 않는다.
- 요약 조회에 실패해도 입력은 유지하며 요약만 다시 조회할 수 있다. 요청 시 프로젝트 권한은 서버에서 다시 검사한다.

처리 요청:

```typescript
const form = new FormData()
form.append('meetingDate', meetingDate)
form.append('content', text) // 파일 방식이면 대신 form.append('file', file)
// POST /api/projects/{projectId}/analysis-tasks
// 202: { taskId, meetingId, status, executionMode }
```

- 접수 시 새 POST를 한 번 호출한다. 기존 업로드·분석 시작 API는 호출하지 않는다. 요청 중 입력·프로젝트 전환·중복 제출을 막는다.
- 실패 시 `{code, message}`의 메시지를 표시하고 입력을 유지한다. 처리 요청은 자동 재시도하지 않는다.
- 202를 받으면 기존 `taskId` 폴링을 시작하고 회의 제목 등은 `GET /api/meetings/{meetingId}`로 보완한다. 메타데이터 조회가 실패해도 접수된 작업은 계속 조회하며 POST를 재호출하지 않는다.
- 담당자 검토·전송 결과 화면과 진행 단계 개편은 별도 Task다. 현재 분석 파이프라인의 발행 정책은 변경하지 않는다. 실제 실행에서는 기존 `DRY_RUN` 설정을 확인한다.
- mock에서도 새 처리 API·회의 메타데이터 조회를 지원한다. 회의 날짜·제목·파일명은 입력값을 따르며 실제 Agent·GitHub는 호출하지 않는다. mock 작업은 메모리에 있어 새로고침 후 작업 복원은 이번 범위 밖이다.

## 프로젝트 목록과 삭제 확인 (TASK-008 / #214)

- 상단 **프로젝트** 메뉴에서 소속 프로젝트의 이름, GitHub 저장소, 도구 연결, 전체 멤버 수, 최근 회의를 표시한다. `GET /api/projects`의 기존 ID·이름 필드는 유지하며 목록을 열 때마다 다시 불러온다.
- 연결 상태 `CONNECTED`는 연결됨, `NOT_CONNECTED`는 미연결, `ERROR`와 `null`은 권한 확인 필요로 표시한다. 저장소나 URL을 입력했다는 이유로 연결 성공을 추정하지 않는다.
- `canEdit=true`인 관리자에게만 편집·삭제 버튼을 표시한다. 삭제 확인 창은 프로젝트 이름이 일치할 때만 삭제를 활성화하며, 처리 중에는 닫기·메뉴 이동·로그아웃·중복 삭제를 막는다. 실패 시 서버 메시지와 입력을 유지한다.
- 목록 재조회 시 업로드 대상으로 선택한 프로젝트도 최신 목록과 대조한다. 삭제·멤버 제외로 사라졌으면 선택과 멤버를 비우고 재선택을 안내한다. 이전 멤버 조회 응답은 무효화하며, 새 프로젝트를 직접 선택하기 전에는 업로드할 수 없다. 목록에 남아 있으면 최신 이름으로 갱신한다.
- 삭제 후 목록을 다시 조회하고, 업로드 화면에서 선택한 프로젝트를 지웠으면 남은 프로젝트로 바꾼다. 없으면 빈 선택으로 돌아간다. 회의 처리 기록·티켓·멤버는 삭제되며 외부에 이미 발행한 결과는 남는다. 기존 이슈 색인 삭제는 서버의 TASK-037 후속 구현 전까지 미지원이므로 확인 창에 이를 명시한다.
- mock도 목록 응답 필드, 관리자 검사, 이름 확인, 처리 중 409와 삭제를 지원한다. 최근 회의는 현재 탭에서 업로드한 회의 날짜로 계산한다. mock 회의·작업은 메모리에 있고 프로젝트·내 티켓은 sessionStorage에 저장된다. 실제 외부 도구·색인을 변경하지 않는다.
- 화면 검증: `demo1 / password1`의 목록·새 프로젝트·편집·삭제, `member2 / password1`의 관리 버튼 숨김, 잘못된 이름 입력, 처리 중 오류와 빈 목록·조회 실패 재시도를 확인한다.

## 프로젝트 관리자 지정·해제 (#172 / TASK-056 화면 연동)

- 프로젝트 멤버 표에서 `members[].admin`으로 **관리자 / 일반 멤버**를 표시한다. GitHub 저장소 권한과 별개의 역할이다.
- `canEdit=true`인 기존 프로젝트 관리자에게만 **관리자 지정 / 관리자 해제** 버튼이 보인다. 새 프로젝트·새로 추가한 멤버는 먼저 저장해야 역할을 변경할 수 있다.
- 버튼 클릭 시 아래 API를 호출한다. 프로젝트 정보 저장과 별도로 즉시 적용되며, 요청 중 역할 변경·저장·화면 전환을 막는다.

```typescript
// PUT /api/projects/{projectId}/members/{memberUserId}/role
{ role: 'ADMIN' } // 해제: { role: 'MEMBER' }
// 200: ProjectDetail (members[].admin, canEdit 포함)
```

- 성공 응답으로 역할·편집 권한을 갱신하며, 편집 중인 이름·설명·도구 입력과 멤버 추가·제거는 덮어쓰지 않는다. 서버 응답에 관리자가 포함되면 제거 대기 중인 멤버라도 유지한다.
- 관리자는 먼저 역할을 해제해야 멤버에서 제거할 수 있다. 마지막 관리자의 역할 해제는 서버가 `409 LAST_PROJECT_ADMIN`으로 거절하며, 메시지를 표시하고 기존 역할을 유지한다.
- 자신의 관리자 권한을 해제해 `canEdit=false`가 되면 읽기 전용으로 전환한다. 편집 중 입력은 남지만 저장할 수 없으므로 자기 권한 해제 전 저장하도록 안내한다.
- 오류 응답은 `{code, message}`의 메시지를 표시한다. 403이면 편집을 막으며 401은 기존 세션 만료 처리를 따른다. 실패한 역할 변경은 성공으로 표시하거나 자동 재시도하지 않는다.
- mock도 동일한 역할·권한·마지막 관리자 조건을 적용한다. `demo1`이 `member2`를 관리자로 지정한 뒤 로그아웃해 `member2 / password1`로 로그인하면 편집할 수 있다. 역할은 기존 프로젝트와 함께 sessionStorage에 저장된다. 실제 DB 동시 요청·비관적 잠금은 mock의 검증 범위가 아니다.

## 분석 상태 조회 (polling)

- `useAnalysisPolling`이 2초마다 `GET /api/analysis-tasks/{taskId}`를 호출한다. SSE는 쓰지 않는다.
- `COMPLETED` / `PARTIALLY_FAILED` / `FAILED`가 되면 멈추고 결과를 불러온다. (`FAILED`여도 항목이 있으면 결과 화면을 보여준다)
- 처리 중 화면을 벗어나면(로그아웃, 처음으로) 조회를 정리한다.
- 서버 오류 / 네트워크 오류가 3번 연속되면 멈추고 안내한다. 4xx는 바로 멈춘다.

### 진행 화면 (TASK-013 / #209)

화면의 현재 단계는 `stage`가 아니라 선택 세부 정보인 `progress.step`을 따른다. 예전의 검수·자동 발행 단계를 새 초안 생성 흐름의 진행 상태로 사용하지 않는다.

| progress.step | 화면 표시 |
|---|---|
| `CONTEXT` | 맥락 파악 |
| `EXTRACTING` | 티켓 추출 (초안 작성·검수 포함) |
| `DEDUPING` | 중복 검수 |
| `DISPATCHING` | 담당자에게 전송 |

- `progress.context`가 있으면 멤버·기존 이슈·Notion 문서 수, `extraction`이 있으면 할 일·결정·미결 질문 수, `dedupe`가 있으면 검수 처리/전체·기존 이슈 연결 건수를 표시한다.
- 아직 도착하지 않은 그룹은 표시하지 않는다. 그룹 안의 누락·null 건수는 **확인 중**, 실제 0은 **0건/0명**으로 구분한다.
- `progress`가 없거나 알 수 없는 단계이면 **회의록을 처리하고 있습니다**로 안내하고 폴링은 계속한다. 실행 단계를 `stage`로 추측하거나 모든 단계를 완료 표시하지 않는다.
- 다음 단계에 도달해도 선택 기능인 중복 검수 정보가 없으면 **진행 정보 없음**으로 표시한다. 실패한 단계는 **확인 필요**로 표시하고 이후 단계는 완료 처리하지 않는다.
- 경과 시간과 기존 종료·오류·세션 만료 처리를 유지한다. 조회와 화면 전환에서 분석 접수 POST를 다시 호출하지 않는다.
- 완료 시 기존 결과 조회 후 #213에서 제공하는 전송 결과 화면으로 전환한다. Agent 처리 상세는 접힌 영역에서 확인할 수 있다. **초안 생성 완료는 티켓 발행 완료가 아니다.** 전송 결과·담당자 검토·발행 기능 자체의 구현은 각각의 담당 Task를 따른다.
- `VITE_USE_MOCK=true npm run dev`로 로그인 후 샘플 파일을 사용하면 네 단계와 누적 세부 정보를 확인할 수 있다. mock은 실제 Agent·GitHub를 호출하지 않으며 결과는 `PENDING` / `DRAFT`로 표시한다. 기존에 발행된 것처럼 보이던 mock 결과를 초안 흐름에 맞췄다.

검증 명령: `npm run test:progress`, `npm run build`, `npm run lint`.
단계 판단 테스트는 Node 기본 테스트 실행기와 이미 설치된 TypeScript를 사용한다. 브라우저 검증·실제 서버 통합 검증은 별도로 수행한다.

## API 호출

백엔드 API는 `/api`로 시작하는 상대 경로로 호출한다. 개발 서버가 `http://localhost:8080`으로 전달하므로 CORS 설정이 필요 없다.

```ts
const res = await fetch('/api/meetings')
```

로그인은 세션 쿠키(`JSESSIONID`)로 유지된다. 같은 출처 요청이라 쿠키가 자동으로 전송되므로 토큰을 따로 저장하지 않는다.

포트와 CORS 규칙은 `docs/dev-env.md` 참고.

## 인증 오류 처리 (#197)

- 첫 로그인 상태 확인(`GET /api/auth/me`)에서 401이면 로그인 화면을 표시한다. 서버·네트워크 오류이면 로그인 여부를 단정하지 않고 오류 안내와 **다시 시도** 버튼을 표시한다.
- **다시 시도**는 로그인 상태 조회만 다시 호출한다. 성공하면 서비스 화면, 401이면 로그인 화면으로 이동한다.
- 로그아웃은 성공 응답을 받은 뒤 화면을 초기화한다. 서버·네트워크 오류이면 현재 화면과 입력을 유지하며 **로그아웃 다시 시도**를 제공한다. 처리 중에는 로그아웃 중복 요청을 막는다.
- 세션 만료(`UNAUTHORIZED`)에 대한 기존 로그인 화면 전환과 만료 안내는 유지한다.
- 확인 시나리오: 세션 조회 500·네트워크 오류 후 재시도 성공/401/반복 실패, 로그인 실패 후 성공, 로그아웃 실패 시 미저장 입력 유지와 재시도, 처리 중 중복 요청 방지, 세션 만료 안내.

## 내 티켓과 카드 검토 (TASK-027·030 / #210)

로그인 후 **내 티켓**에서 본인이 담당인 티켓을 조회한다. **프로젝트 필터**와 검토 대기·확인·거절 건수는 `GET /api/tickets/me` 응답을 사용한다. 전체 프로젝트 또는 개별 프로젝트를 선택할 수 있으며, 로딩·빈 목록·조회 오류와 재시도를 구분한다.

- **수정**: 검토 대기 카드의 본문·담당자·기한 문구/날짜/시각을 편집한다. `PATCH /api/tickets/{ticketId}`에는 변경한 항목만 보낸다. 본문은 공백만 입력할 수 없고 4,000자 이하, 기한 문구는 200자 이하이다. 기한 항목을 모두 비우면 기한을 지운다.
- 담당자는 해당 프로젝트의 멤버 조회 API에서 선택한다. 담당자 변경을 저장하면 새 담당자의 내 티켓으로 옮겨지므로 내 목록에서 제거한다. 멤버 조회에 실패해도 본문·기한 수정은 가능하며, 담당자 목록만 다시 조회할 수 있다.
- **확인 / 거절**: 검토 대기에서 각각 확인·거절 상태로 바꾼다. 확인한 카드는 **다시 열기**, 거절한 카드는 **되돌리기**로 검토 대기로 돌린다. 편집 중에는 저장·취소 후 상태를 바꾼다. 발행이 끝난 카드는 읽기 전용이다.
- 저장·상태 변경에 성공하면 응답을 반영하고 목록·건수를 다시 조회한다. 변경 요청은 성공했는데 후속 조회만 실패한 경우 변경을 재전송하지 않고 목록 조회만 재시도한다.
- 실패 시 입력을 유지한다. 403·404·409이면 재변경을 막고 **최신 내용 불러오기**를 안내한다. 최신 조회에 성공하면 서버 기준으로 다시 검토할 수 있다. 401 `UNAUTHORIZED`는 기존 세션 만료 안내로 처리한다.
- 변경 요청 중에는 중복 동작·프로젝트 필터 변경·화면 이동·로그아웃을 막는다. 미저장 입력이 있으면 취소·필터 변경·메뉴 이동·로그아웃 시 폐기 여부를 확인하고, 브라우저 새로고침·닫기는 기본 경고를 사용한다.
- 발행 대상·처리 유형은 읽기 전용으로 표시한다. 변경 UI와 중복 판단 상세(TASK-032), 일괄 발행(TASK-033), 발행 결과·실패 배너(TASK-047·048)는 이번 범위에 포함하지 않는다.

### mock 확인

`VITE_USE_MOCK=true npm run dev`로 실행하고 `demo1 / password1`로 로그인한 뒤 **내 티켓**을 연다. 기본 프로젝트에 검토 대기 2건·확인 1건·거절 1건이 있다. 가입만 한 새 사용자는 전달받은 티켓이 없어 빈 목록이 표시된다. mock 티켓은 화면 검증용 별도 회의 데이터이며 회의록 분석 결과와 자동 연결되지 않는다.

수정·상태·담당자 변경은 탭의 `sessionStorage`에 보존된다. 다른 멤버에게 담당자를 바꾼 뒤 로그아웃하고 그 멤버의 `member{사용자ID} / password1`로 로그인하면 이동한 티켓을 볼 수 있다. 티켓 mock만 초기화하려면 개발자 도구에서 `sessionStorage.removeItem('mock-my-tickets-v1')`을 실행하고 새로고침한다. mock 성공은 실제 서버·DB 검증을 대신하지 않으며 실제 API 실패를 mock으로 대체하지 않는다.


### TASK-048·050: 내 티켓 실패 배너와 프로젝트 설정 이동 (#239)

- 선택한 프로젝트의 `counts.failed`가 1건 이상이면 상단에 발행 실패 배너를 표시한다. 목록 갱신 실패 시 이전 조회 값임을 함께 안내한다.
- `실패한 티켓만 보기`는 현재 프로젝트 안에서 `FAILED` 티켓만 표시한다. `전체 티켓 보기`로 돌아갈 수 있으며, 필터 변경 시 저장하지 않은 수정은 확인 후 버린다.
- 마지막 실패 티켓의 재시도 성공 등으로 실패가 없어지면 배너가 사라지고 실패 목록에는 빈 상태를 안내한다.
- `failureCategory=REPOSITORY_PERMISSION`인 카드의 `프로젝트 설정 확인`은 클릭 시 `GET /api/projects/{projectId}`로 현재 권한을 확인한다. 업로드 화면의 선택 프로젝트가 아닌 **실패 티켓이 속한 프로젝트**를 연다.
- 관리자는 해당 프로젝트 편집 화면으로 이동하며, 일반 멤버는 관리자에게 저장소 연결·토큰·접근 권한 확인을 요청하도록 안내한다. 일반 멤버에게 편집 권한을 부여하지 않는다.
- 403(접근 권한), 404(삭제/조회 불가), 연결 오류를 구분해 안내한다. 실패하면 티켓 입력은 유지하고 다시 설정 확인을 누를 수 있다. 401은 기존 로그인 만료 처리를 따른다.
- 미저장 수정이 있으면 이동 전에 확인하며 취소 시 프로젝트 조회도 실행하지 않는다. 확인·이동은 GET만 호출하며 발행·재시도를 자동으로 실행하지 않는다.
- mock 기본 데이터에 저장소 권한 실패 1건과 결과 불명확 실패 1건을 포함한다. 실제 외부 API는 호출하지 않는다.
- 백엔드/Agent/API 계약·의존성 변경 없음.
