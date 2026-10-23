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
| GET | `/api/projects` | 내 프로젝트 목록 + 백엔드 실행 모드(`MOCK` / `LIVE`). 지금은 첫 프로젝트를 쓴다 |
| GET | `/api/projects/{projectId}/members` | 팀원 목록. 참여자 ID를 이름으로 표시 |
| POST | `/api/projects/{projectId}/meetings` | .txt 회의록 업로드 (multipart, `file`) |
| POST | `/api/meetings/{meetingId}/analysis-tasks` | 분석 시작 (202, `taskId`) |
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

로그인 후 **프로젝트 설정**에서 기존 프로젝트를 선택하거나 **새 프로젝트 등록**을 고른다. 소속 프로젝트가 없는 사용자도 새 프로젝트를 만들 수 있다. 프로젝트 목록·삭제 화면(TASK-008·009)은 이번 범위에 포함하지 않는다.

- 기본 정보: 이름(필수), 설명(선택)
- 도구 연결 정보: GitHub `소유자/저장소`(필수), Notion 기획 페이지 URL·Slack 채널 ID(선택)
- 멤버: 가입한 사용자를 이름·GitHub 로그인명으로 검색해 추가·제거한다. 동명이인은 GitHub 로그인명으로 구분한다. GitHub 계정과 저장소 권한은 읽기 전용이다.
- 권한: 상세 응답의 `canEdit=false`이면 읽기 전용으로 표시한다. 저장 API의 403도 안내한다. 실제 권한 검사는 서버가 담당한다.
- 저장: 필수값·저장소 형식을 검사하고 저장 중 중복 요청을 막는다. 실패하면 입력을 유지한다. GitHub 저장소를 바꾸거나 멤버를 추가하면 기존 권한을 새 권한처럼 표시하지 않고 저장 후 확인하도록 안내한다.
- 기존 분석 진행 중에는 프로젝트 설정 이동을 막아 진행 화면의 상태를 유지한다.

### TASK-005·006 연동 계약

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

## API 호출

백엔드 API는 `/api`로 시작하는 상대 경로로 호출한다. 개발 서버가 `http://localhost:8080`으로 전달하므로 CORS 설정이 필요 없다.

```ts
const res = await fetch('/api/meetings')
```

로그인은 세션 쿠키(`JSESSIONID`)로 유지된다. 같은 출처 요청이라 쿠키가 자동으로 전송되므로 토큰을 따로 저장하지 않는다.

포트와 CORS 규칙은 `docs/dev-env.md` 참고.
