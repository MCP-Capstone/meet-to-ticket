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

## API 호출

백엔드 API는 `/api`로 시작하는 상대 경로로 호출한다. 개발 서버가 `http://localhost:8080`으로 전달하므로 CORS 설정이 필요 없다.

```ts
const res = await fetch('/api/meetings')
```

포트와 CORS 규칙은 `docs/dev-env.md` 참고.
