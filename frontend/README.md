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

백엔드 없이 화면을 개발할 수 있도록 기본값은 가짜 API(mock)를 사용한다. 화면 오른쪽 위에 `MOCK` 표시가 나온다.

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
├── components/       # MeetingForm, ActionItemList
└── App.tsx           # 입력 → 추출 → 확인 → 티켓 생성 흐름
```

## 사용하는 API

| Method | URL | 설명 |
|---|---|---|
| POST | `/api/meetings` | 회의록 등록 |
| POST | `/api/meetings/{meetingId}/extract` | 할 일 추출 |
| POST | `/api/tickets` | 전체 할 일로 티켓 생성 |

## API 호출

백엔드 API는 `/api`로 시작하는 상대 경로로 호출한다. 개발 서버가 `http://localhost:8080`으로 전달하므로 CORS 설정이 필요 없다.

```ts
const res = await fetch('/api/meetings')
```

포트와 CORS 규칙은 `docs/dev-env.md` 참고.
