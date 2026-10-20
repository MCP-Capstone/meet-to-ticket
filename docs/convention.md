# Git 컨벤션

## 브랜치 전략

간소화한 Git Flow를 사용한다.

```
feat/12-xxx ─┐
fix/13-xxx  ─┼─▶ develop ──(배포/발표 시점)──▶ main
docs/14-xxx ─┘
```

| 브랜치 | 역할 |
|---|---|
| `main` | 배포/발표 가능한 안정 버전. `develop`에서만 머지한다. |
| `develop` | 기본 브랜치. 모든 작업 브랜치는 여기서 만들고 여기로 머지한다. |
| 작업 브랜치 | 이슈 하나당 브랜치 하나. 머지 후에도 자동 삭제되지 않는다. |

### 금지 사항

- `main`, `develop`에 직접 push 하지 않는다. 반드시 PR로 머지한다.
- `main`, `develop`을 삭제하지 않는다. (`git push origin --delete main` 등)
- `main`, `develop`에 force push 하지 않는다.

## 작업 흐름

1. 이슈를 발행한다. (이슈 템플릿 사용)
2. 최신 `develop`에서 작업 브랜치를 만든다.
   ```bash
   git checkout develop && git pull origin develop
   git checkout -b <type>/<issue-number>-<description>
   ```
3. 작업 후 커밋하고 push 한다.
4. `develop`을 대상으로 PR을 만든다. 본문에 `Closes #<issue-number>`를 포함한다.
5. 머지 후 로컬 `develop`을 갱신한다.

릴리즈 시점에는 `develop` → `main` PR을 만들어 머지한다.

## 브랜치 이름

`<type>/<issue-number>-<description>`

- `description`은 영문 소문자와 하이픈만 사용한다.
- 예: `feat/12-meeting-upload`, `fix/13-login-redirect`

## 커밋 메시지

`<type>(<scope>): <subject> (#<issue-number>)`

- `scope`는 선택이다. 영역을 밝힐 때 사용한다. (`frontend`, `backend`, `agent`, `mcp-server`, `docs` 등)
- `subject`는 한국어 명사형으로 끝낸다. (예: "추가", "수정", "제거")
- 제목 한 줄만 작성한다.
- 예: `feat(backend): 회의록 업로드 API 추가 (#12)`

## 타입

| 타입 | 설명 |
|---|---|
| `feat` | 새로운 기능 추가 |
| `fix` | 버그 수정 |
| `docs` | 문서 작성 및 수정 |
| `chore` | 설정, 빌드 등 기타 작업 |
| `refactor` | 기능 변화 없는 코드 개선 |
| `style` | 포맷 등 스타일 수정 |
| `test` | 테스트 추가 및 수정 |
| `perf` | 성능 개선 |

이슈와 PR에는 같은 이름의 라벨을 붙인다.

## 이슈 / PR 제목

- 이슈: `<type>(<scope>): <subject>`
- PR: `<type>(<scope>): <subject> (#<issue-number>)`
