# 데이터 출처와 이용조건

이 서비스가 쓰거나 저장소에 둔 데이터의 출처, 이용조건, 개인정보 여부를 정리한 문서다. 외부에서 가져온 데이터 세트는 쓰지 않았다. 아래 데이터는 모두 팀이 직접 만든 것이다.

| 데이터 | 위치 | 출처 | 이용조건과 개인정보 |
|---|---|---|---|
| 평가셋 v2.1 (입력 요약본 30개, 정답 reference, 명단) | `agent/eval/dataset/v2.1/` | 팀이 직접 쓴 가상 회의 요약이다. 요약본의 근거 대화 편집본(원본 회의록)은 저장소에 두지 않고 팀 내부 자료로 보관한다 | 참석자 이름은 팀원 실명이다. 이메일, 전화번호, 로그인명은 모두 가짜 값이다(`example.test`, `010-0000-0000`, `테스트용-로그인-N`). 실제 개인정보는 없다 |
| 맥락 평가셋 context-v1 | `agent/eval/dataset/context-v1/` | 팀이 만든 가상 기획 문서와 가상 이슈 목록(`example-test` 저장소 주소)이다 | 실제 저장소와 문서를 가리키지 않는다. 실제 개인정보는 없다 |
| 샘플 회의록 3개 (`10min_1`, `30min_1`, `60min_1`) | `backend/examples/meetings/` | 평가셋과 같은 팀 작성 가상 회의 요약이다 | 위와 같다 |
| 화면 확인용 모의 데이터 | `frontend/src/mocks/data.ts` | 팀이 만든 가짜 결과이고 실제 Agent나 GitHub 결과가 아니다 | 실제 개인정보는 없다 |
| 중복 판단 대조 저장소 | `MCP-Capstone/meet-to-ticket-eval` (Private) | 팀이 만든 시험용 이슈 8개다. 목록은 `agent/eval/dataset/v2.1/dedupe_seed_issues.md`에 있다 | 시드 이슈를 고치거나 닫지 않는다. 평가 실행은 항상 dry-run이라 이 저장소에 쓰지 않는다 |
| 발행 시험 대상 저장소 | `MCP-Capstone/mcp-capstone` | 팀의 시험용 저장소다. 안의 이슈는 발행 시험으로 만든 것이다 | 실제 사용자 정보는 없다 |
| 프로젝트 기획 문서 | Notion 기획 문서 (팀 내부) | 팀이 작성했다 | 저장소에는 포함하지 않았다 |

## 외부 서비스와 모델

| 서비스 | 용도 | 비고 |
|---|---|---|
| OpenAI API (`gpt-5.4-nano`, `gpt-5.4-mini`, `text-embedding-3-small`) | 할 일 추출, 초안, 검수, 중복 판단, 기존 이슈 임베딩 | 회의 원문과 기존 이슈 본문이 LLM 호출 때 OpenAI로 전송된다. 서비스에 올리는 회의록에는 개인정보를 넣지 않는다 |
| GitHub, Notion, Slack API | 이슈, 페이지, 공지 발행과 조회 | 토큰은 `business-mcp/.env`, `rag-mcp/.env`에만 둔다. 쓰기 가능한 저장소는 `GITHUB_ALLOWED_REPOS`로 제한할 수 있다 |

API 키와 토큰은 저장소에 올리지 않는다(`.gitignore`가 `.env`를 제외하고 `.env.example`만 둔다). 저장소에는 별도의 라이선스 파일이 없다.
