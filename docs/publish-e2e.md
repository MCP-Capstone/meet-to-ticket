# 발행 E2E 검증과 발행 품질 지표

관련 이슈: #303. 발행 멈춤 재현과 시간 상한은 `docs/publish-hang.md`(#299, #302)에 있다.

확인한 티켓이 GitHub, Notion, Slack에 규칙대로, 한 번만, 제시간에 발행되는지 실제 대상에서 확인한다. AI 판단 품질(추출, 중복, 초안)과 별개로 "발행을 잘 하는가"를 수치로 남긴다.

## 검증 방식

| 층 | 방식 | 확인하는 것 |
|---|---|---|
| ① 발행 경로 | 로컬 business-mcp(실제 토큰) + Agent `run_publish_batch`에 정해진 티켓을 넣어 실제 대상에 발행하고, 각 서비스 API로 다시 조회한다 | 대상별 필드 정확도, 성공률, 실패 조건별 오류 코드 |
| ② 전체 흐름 | `compose.full.yml`에서 Backend API로 발행을 요청하고 결과 조회가 끝날 때까지 잰다 | 요청부터 결과 반영까지 걸린 시간, 동시 요청과 Agent 재시작 뒤 중복 여부(publications 기록과 외부 API 대조) |

①에서 티켓을 직접 넣는 이유: 제품 흐름에서 LINK/UPDATE는 중복 판단이 찾은 기존 이슈에만 가능하고 담당자가 이슈 번호를 지정할 수 없다. 중복 판단(rag-mcp, LLM)을 거치지 않고 발행 경로만 결정적으로 확인하기 위해 처리 유형과 대상 이슈를 정해 넣는다.

## 실행

```bash
cd agent
python -m eval.publish_e2e --notion-page-url <Notion 상위 페이지> --slack-channel <채널 ID>          # 계획만 출력
python -m eval.publish_e2e --notion-page-url <Notion 상위 페이지> --slack-channel <채널 ID> --yes    # 실제 발행
python -m eval.publish_e2e ... --only DUP-GH-CREATE,DUP-GH-RETITLE --retry-delay 20 --yes              # 재시도 전 20초 대기
```

- business-mcp 가상환경이 필요하다 (`cd business-mcp && uv venv --python 3.12 && uv pip sync --python .venv/bin/python requirements-dev.txt`). 토큰은 `business-mcp/.env`에서 읽고 출력하지 않는다.
- `--yes` 없이는 발행하지 않는다. GitHub는 `--repo`(기본 `MCP-Capstone/mcp-capstone`)만 쓴다.
- 끝나면 만든 GitHub 이슈는 닫고, Notion 페이지는 보관하고, Slack 메시지는 지운다 (`--keep`이면 남긴다). GitHub는 API로 이슈를 지울 수 없어 닫힌 이슈가 남는다. 제목은 `[E2E #303 <실행 ID>]`로 시작한다.
- 결과는 `agent/eval/results/publish_e2e/<실행 ID>.json`, `.md`에 남는다 (커밋하지 않음).
- LLM은 부르지 않는다. 한 번 실행에 1분이 걸리지 않는다.

## 시나리오

| ID | 조건 | 대상 | 처리 유형 | 기대 | 확인 |
|---|---|---|---|---|---|
| GH-CREATE | 정상 | GitHub | CREATE | 발행됨 | 제목, 본문 = 미리보기(`publication_preview`), 라벨 반영, 없는 라벨은 빠지고 `dropped_labels`로 보고 |
| GH-LINK | 정상 | GitHub | LINK | 연결됨 | GH-CREATE 이슈에 미리보기와 같은 연결 댓글 |
| GH-UPDATE | 정상 | GitHub | UPDATE | 갱신됨 | 본문 끝에 미리보기와 같은 변경 섹션, 알림 댓글, 라벨 추가 |
| NOTION-CREATE | 정상 | Notion | CREATE | 발행됨 | 페이지 제목, 본문 블록 = 출력문(`build_notion_page`) |
| SLACK-CREATE | 정상 | Slack | CREATE | 게시됨 | 메시지 링크, 본문 = 발행 본문(`description`) |
| GH-NO-ACCESS | 권한 없음 | GitHub | CREATE | 실패 | `REPOSITORY_NOT_ALLOWED`·`GITHUB_NOT_FOUND`·`GITHUB_AUTH`, 결과 모름 아님 |
| NOTION-NO-ACCESS | 권한 없음 | Notion | CREATE | 실패 | `NOTION_*`, 결과 모름 아님 |
| SLACK-NO-ACCESS | 권한 없음 | Slack | CREATE | 실패 | `SLACK_*`, 결과 모름 아님 |
| NOTION-NO-ROOT | 설정 누락 | Notion | CREATE | 실패 | `NOTION_ROOT_MISSING` |
| SLACK-NO-CHANNEL | 설정 누락 | Slack | CREATE | 실패 | `INVALID_ARGUMENT` |

중복 시나리오 (①, 처음 발행 뒤 `previous_outcome_unknown`으로 `--retry-delay`초 뒤 다시 보낸다. 기본 0초)

| ID | 대상 | 처리 유형 | 기대 | 확인 |
|---|---|---|---|---|
| DUP-GH-CREATE | GitHub | CREATE | 재시도가 기존 이슈를 돌려줌 | 본문 끝줄 work_key가 같은 이슈 1건 (목록 API로 셈), 검색 API가 찾기까지 걸린 시간 |
| DUP-GH-RETITLE | GitHub | CREATE | 제목을 고친 재시도도 기존 이슈를 돌려줌 (#320) | 같음 |
| DUP-GH-LINK | GitHub | LINK | 연결됨, 댓글 추가 없음 | 같은 연결 댓글 1건 |
| DUP-GH-UPDATE | GitHub | UPDATE | 갱신됨, 섹션 추가 없음 | 같은 변경 섹션과 알림 댓글 1건 |
| DUP-NOTION | Notion | CREATE | `NOTION_VERIFY_REQUIRED`로 막힘 | 재시도가 페이지를 만들지 않음 |
| DUP-SLACK | Slack | CREATE | `SLACK_VERIFY_REQUIRED`로 막힘 | 채널에 같은 본문 1건 |

중복 시나리오 (②, Backend API로 실제 티켓을 발행)

| ID | 만드는 방법 | 기대 |
|---|---|---|
| B-CONCURRENT | 같은 티켓에 발행 요청 3개를 동시에 보낸다 | 1개만 202, 나머지 409. 이슈 1건, publications 1행 |
| B-LOST-GH | 콜백을 끈 Agent(`BACKEND_BASE_URL=""`)로 발행하고 Backend를 일시정지해 결과 조회를 막은 채 Agent를 강제 종료한다. 외부에는 써졌지만 결과를 잃는다 | `RESULT_LOST`, publications `UNCERTAIN`. 다시 시도하면 기존 이슈를 찾아 1건 |
| B-LOST-SLACK | 같은 방법, Slack 티켓 | `RESULT_LOST`, `UNCERTAIN`. 다시 시도는 확인 전까지 409, 메시지 1건 |

Backend 일시정지만으로는 결과를 잃지 않는다. 멈춘 컨테이너도 커널이 연결을 받아 두기 때문에 재개하면 콜백이 반영된다. 그래서 콜백을 끄고 조회도 막았다.

429와 시간 초과는 실제 대상에서 일부러 만들기 어려워 다루지 않는다. MCP 픽스처와 SIGSTOP 자동 테스트(`agent/tests/test_publish_mcp_http.py`, #302)가 맡는다.

## 지표

| 지표 | 정의 |
|---|---|
| 발행 성공률 | 정상 시나리오 중 성공(발행됨/연결됨/갱신됨/게시됨)한 비율 |
| 필드 정확도 | 정상 시나리오의 필드 확인 중 통과한 비율 |
| 실패 조건 기대 일치 | 실패 시나리오 중 기대한 오류 코드로, 결과 모름 없이 끝난 비율 |
| 처리 시간 | ① 발행 경로 한 건(재대조 제외), ② Backend 요청부터 결과 조회까지 |
| 중복 발행 수 | 재시도, 재시작, 동시 요청 뒤 같은 티켓으로 외부에 생긴 항목 중 1건을 넘는 수 |

## 결과

2026-10-24, develop `cf6010c`(#318 포함). GitHub `MCP-Capstone/mcp-capstone`, 프로젝트에 설정된 Notion 페이지와 Slack 채널.

| 지표 | 값 |
|---|---|
| 발행 성공률 | 5/5 |
| 필드 정확도 | 12/12 |
| 실패 조건 기대 일치 | 5/5 |
| 처리 시간 ① 발행 경로 | 중앙값 0.98초, 최대 3.24초 (GH-UPDATE: 본문 조회 + 갱신 + 댓글) |
| 처리 시간 ② 전체 흐름 | 4.93초 (Backend 묶음 생성~종료 4.77초, GitHub CREATE 재시도 1건, 결과 모름 뒤 재시도라 기존 이슈 검색 포함). 화면은 2초마다 조회하므로 최대 2초가 더해진다 |
| 중복 발행 수 | GitHub CREATE 재시도에서 3건 (아래 "중복 발행 결과"). LINK, UPDATE, Notion, Slack, 동시 요청은 0건 |

| 시나리오 | 결과 | 오류 코드 | 시간(초) |
|---|---|---|---|
| GH-CREATE | 발행됨 | - | 1.55 |
| GH-LINK | 연결됨 | - | 0.98 |
| GH-UPDATE | 갱신됨 | - | 3.24 |
| NOTION-CREATE | 발행됨 | - | 0.64 |
| SLACK-CREATE | 게시됨 | - | 0.75 |
| GH-NO-ACCESS | 실패 | `REPOSITORY_NOT_ALLOWED` | 0.04 |
| NOTION-NO-ACCESS | 실패 | `NOTION_NOT_FOUND` | 0.10 |
| SLACK-NO-ACCESS | 실패 | `SLACK_NOT_FOUND` | 0.31 |
| NOTION-NO-ROOT | 실패 | `NOTION_ROOT_MISSING` | 0.02 |
| SLACK-NO-CHANNEL | 실패 | `INVALID_ARGUMENT` | 0.01 |

## 중복 발행 결과

2026-10-24, develop `48b8762`(#319, #321 포함).

| 시나리오 | 재시도까지 | 결과 | 외부 항목 |
|---|---|---|---|
| DUP-GH-CREATE | 0초 | 재시도가 새 이슈를 만듦 | **2건** |
| DUP-GH-RETITLE | 0초 | 같음 | **2건** |
| DUP-GH-CREATE | 20초 | 기존 이슈를 돌려줌 | 1건 |
| DUP-GH-RETITLE | 20초 | 기존 이슈를 돌려줌 (본문 끝줄로 찾음) | 1건 |
| DUP-GH-LINK | 0초 | 연결됨, 댓글 추가 없음 | 1건 |
| DUP-GH-UPDATE | 0초 | 갱신됨, 섹션 추가 없음 | 1건 |
| DUP-NOTION | 0초 | `NOTION_VERIFY_REQUIRED` | 1건 |
| DUP-SLACK | 0초 | `SLACK_VERIFY_REQUIRED` | 1건 |
| B-CONCURRENT | 동시 | 202 1개, 409 2개. publications `CONFIRMED` 1행 | 1건 |
| B-LOST-GH (티켓 2개) | 약 76초 | 62초 뒤 `RESULT_LOST`, publications `UNCERTAIN`(`outcome_unknown`). 재시도 결과 하나는 기존 이슈를 찾았고, 하나는 새로 만듦 | 1건, **2건** |
| B-LOST-SLACK | 약 76초 | `RESULT_LOST`, `UNCERTAIN`. 재시도 409 | 1건 |

GitHub 검색 API와 목록 API에 새 이슈가 보이기까지 걸린 시간 (3회): 목록 3.2~7.5초, 검색 1.6~7.5초. B-LOST-GH에서는 만든 지 76초가 지나도 검색이 이슈를 찾지 못한 경우가 있었다.

### 수정 뒤 (#336)

Backend는 GitHub 신규 이슈가 결과 모름이면 결과 모름이 된 뒤 3분(`PUBLISH_GITHUB_RECHECK_DELAY`)이 지나야 다시 보내고, Agent는 검색에서 못 찾으면 최근 열린 이슈 목록도 확인한다 (`docs/publications.md`).

| 시나리오 | 재시도까지 | 결과 | 외부 항목 |
|---|---|---|---|
| B-LOST-GH (티켓 2개) | 약 62초 (결과 모름 직후) | 429 `PUBLISH_RETRY_TOO_SOON`, Agent에 보내지 않음 | 1건, 1건 |
| B-LOST-GH (같은 티켓) | 약 3분 | 둘 다 기존 이슈를 찾아 발행됨, publications `CONFIRMED` | 1건, 1건 |
| B-LOST-SLACK | 약 62초 | 409 (확인 전 재발행 차단, 변화 없음) | 1건 |
| DUP-GH-CREATE, DUP-GH-RETITLE (Agent만, 2회) | 0초 | 새 이슈를 만듦. 만든 지 1초도 안 돼 목록 API에도 없다 | 2건 |

Agent만 바로 다시 부르는 DUP 시나리오는 Backend 대기를 거치지 않으므로 여전히 중복이다. 제품 경로에서는 Backend가 3분 전 재시도를 막는다.

## 발견한 점

- **GitHub CREATE의 결과 모름 재시도는 중복 이슈를 만들 수 있다:** 재시도 때 기존 이슈를 찾는 경로(`_existing_issue`)가 GitHub 검색 API(`/search/issues`)에 기대는데, 검색 색인은 늦게 반영된다. 결과 모름 직후 재시도는 매번 중복을 만들었고(2/2), Agent 재시작 뒤 76초가 지난 재시도에서도 1/2이 중복이었다. 목록 API도 새 이슈가 몇 초 늦게 보여 검색을 목록으로 바꾸는 것만으로는 막지 못한다. publications는 같은 키의 동시 발행을 막지만 외부 반영 여부는 모르므로 이 경우를 막지 못한다. #336에서 Backend 재시도 대기와 Agent 목록 확인으로 막았다 (위 "수정 뒤").
- **동시 요청, LINK/UPDATE, Notion/Slack은 중복이 없었다:** 동시 요청은 티켓 잠금과 publications가 하나만 통과시켰다. LINK/UPDATE는 이슈 번호를 알고 댓글/본문을 직접 조회하므로 색인 지연이 없다. Notion/Slack은 확인 전 재발행을 막는다.

- **문서와 코드 불일치 (수정함):** `docs/publish-targets.md`는 Slack 본문을 "공지 문구(`slack.text`) 우선"이라고 적었지만, 코드는 #312부터 담당자가 저장한 `description`을 게시하고 없을 때만 `slack.text`를 쓴다. 첫 실행에서 Slack 본문 비교가 실패해 찾았고, 문서를 코드에 맞췄다.
- **GitHub 권한 없음은 허용 저장소 검사가 먼저 막는다:** `GITHUB_ALLOWED_REPOS`가 설정돼 있어 토큰이 접근할 수 없는 저장소도 GitHub를 부르기 전에 `REPOSITORY_NOT_ALLOWED`로 끝난다. GitHub가 실제로 거절하는 경우(`GITHUB_NOT_FOUND`, `GITHUB_AUTH`)는 이 실행에서 확인하지 않았다.
- **Slack 봇은 채널 기록을 읽을 수 있다:** `channels:history` 권한이 있어 게시한 본문을 다시 조회해 비교했다. 권한이 없는 환경에서는 본문 비교를 건너뛰고 결과에 그렇게 적는다.
- **실패 시나리오는 모두 보내기 전에 끝났다:** 결과 모름(UNKNOWN)으로 남은 실패가 없어 담당자가 외부 도구를 확인할 필요가 없다.

## 남은 일

- GitHub 색인이 3분 넘게 늦으면 여전히 못 찾을 수 있다. 결과를 잃지 않게 Agent 발행 결과를 디스크에 남기는 일은 별도 작업이다 (#336 범위 밖).
- 복구 워커(#319)가 정리하는 "티켓은 기다리지 않는데 진행 중으로 남은 기록"은 실제 대상에서 만들지 않았다. `PublicationRecoveryTest`가 맡는다.
