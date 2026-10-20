# frontend

React + TypeScript + Vite

## 실행

```bash
npm install
npm run dev
```

http://localhost:5173 에서 열린다.

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
| POST | `/api/projects/{projectId}/meetings` | .txt 회의록 업로드 (multipart, `file`) |
| POST | `/api/meetings/{meetingId}/analysis` | 분석 시작 (202, `jobId`) |
| GET | `/api/analysis/{jobId}` | 분석 상태 / 현재 단계 (2초 간격 조회) |
| GET | `/api/analysis/{jobId}/results` | 항목별 결과 |

> 업로드 / 분석 / 결과 API의 경로와 필드는 임시다. 확정 스키마와 Swagger 명세가 나오면 `src/api/types.ts`, `src/api/real.ts`를 맞춘다.

## 분석 상태 조회 (polling)

- `useAnalysisPolling`이 2초마다 `GET /api/analysis/{jobId}`를 호출한다. SSE는 쓰지 않는다.
- `COMPLETED` / `FAILED`가 되면 멈추고 결과를 불러온다.
- 처리 중 화면을 벗어나면(로그아웃, 처음으로) 조회를 정리한다.
- 서버 오류 / 네트워크 오류가 3번 연속되면 멈추고 안내한다. 4xx는 바로 멈춘다.

## API 호출

백엔드 API는 `/api`로 시작하는 상대 경로로 호출한다. 개발 서버가 `http://localhost:8080`으로 전달하므로 CORS 설정이 필요 없다.

```ts
const res = await fetch('/api/meetings')
```

로그인은 세션 쿠키(`JSESSIONID`)로 유지된다. 같은 출처 요청이라 쿠키가 자동으로 전송되므로 토큰을 따로 저장하지 않는다.

포트와 CORS 규칙은 `docs/dev-env.md` 참고.
