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

- 데모 계정 `demo` / `demo1234`로 바로 로그인할 수 있다.
- mock에서 가입한 계정과 로그인 상태는 브라우저 탭을 닫기 전까지(sessionStorage) 유지된다.

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
├── mocks/            # 가짜 API와 샘플 데이터
├── components/       # AuthForm, Stepper, MeetingForm, ExtractingView, ActionItemList
└── App.tsx           # 로그인 확인 → 입력 → 추출 → 결과 확인 흐름
```

## 사용하는 API

| Method | URL | 설명 |
|---|---|---|
| POST | `/api/auth/signup` | 회원가입 (가입 후 바로 로그인) |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/auth/logout` | 로그아웃 |
| GET | `/api/auth/me` | 로그인 상태 확인 (새로고침 시) |
| POST | `/api/meetings` | 회의록 등록 |
| POST | `/api/meetings/{meetingId}/extract` | 할 일 추출 |

## API 호출

백엔드 API는 `/api`로 시작하는 상대 경로로 호출한다. 개발 서버가 `http://localhost:8080`으로 전달하므로 CORS 설정이 필요 없다.

```ts
const res = await fetch('/api/meetings')
```

로그인은 세션 쿠키(`JSESSIONID`)로 유지된다. 같은 출처 요청이라 쿠키가 자동으로 전송되므로 토큰을 따로 저장하지 않는다.

포트와 CORS 규칙은 `docs/dev-env.md` 참고.
