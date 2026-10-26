# V-02 발행 실패 화면 캡처 (TC-06 근거)

실행일 2026-10-26, 전체 Docker 구성(develop c0f626d), 계정 demo1(함주헌), 프로젝트 1(대상 저장소 MCP-Capstone/mcp-capstone).
입력은 GitHub 구현 업무 2건이 든 회의 요약("회의 요약 — 발행 실패 처리 검증 (TC-06)", 회의 12)이다. Slack 회의 결과 공지 티켓은 거절했다.

| 캡처 | 만든 방법 | 결과 (발행 묶음 API) | 화면 |
|---|---|---|---|
| 2026-10-26-tc06-permission-denied.png | 실제 발행 모드(DRY_RUN=false)에서 business-mcp의 GITHUB_ALLOWED_REPOS를 다른 저장소로 바꾸고 발행 | FAILED, errorCode REPOSITORY_NOT_ALLOWED, failureCategory REPOSITORY_PERMISSION, retryable false | 실패 사유, "바로 다시 시도할 수 없어요", 프로젝트 설정 안내. "다시 시도" 비활성, "프로젝트 설정 확인"과 "수정"만 가능 |
| 2026-10-26-tc06-mcp-unavailable-retryable.png | 같은 설정에서 business-mcp 컨테이너를 멈추고 발행 | FAILED, errorCode MCP_UNAVAILABLE, failureCategory OTHER, retryable true, outcomeUnknown false | 실패 사유 표시, "다시 시도" 활성 |

- 두 경우 모두 GitHub에 요청이 가기 전에 막혀 실제 이슈는 만들어지지 않았다.
- 판정: 실패 사유가 보이고, 재시도 가능한 실패와 권한 오류가 화면에서 구분된다. TC-06 기대 결과와 같다.
- 발견: MCP_UNAVAILABLE은 안내 문구가 없어 서버 사유("unhandled errors in a TaskGroup ...")가 그대로 보인다(frontend/src/publishFailure.ts의 guides에 없음). 수정하지 않았다.
- 끝난 뒤 설정(DRY_RUN=true, GITHUB_ALLOWED_REPOS=MCP-Capstone/mcp-capstone)을 되돌리고 컨테이너를 다시 만들었다.
