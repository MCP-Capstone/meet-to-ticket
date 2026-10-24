# 발행 멈춤·무한대기 재현

관련 이슈: #299(재현·측정), #302(시간 상한·유실 처리).

발행 버튼을 누른 뒤 화면이 "발행 중"에서 오래 멈출 수 있는 지점을 단계별로 정리하고, 재현 테스트와 실측 결과를 남긴다. 아래 "발행 경로와 시간 상한"과 "측정 결과"는 #299 당시(수정 전) 기록이고, 바뀐 내용은 "#302 수정 후"에 있다.

## 발행 경로와 시간 상한 (#299, 수정 전)

```
화면 ──2초 폴링──▶ Backend ──POST /publish──▶ Agent ──MCP 도구 호출──▶ Business MCP ──▶ GitHub / Notion / Slack
                 ◀─5초마다 GET /publish/{id}─┘      ◀── 결과 콜백 ──┘   └──▶ RAG MCP (재대조·색인)
```

| 단계 | 현재 시간 상한 | 끝나지 않을 때 | 위치 |
|---|---|---|---|
| 화면 결과 폴링 | 없음. RUNNING이면 2초마다 계속 조회 | 스피너만 표시, 경과 시간·안내 없음 | `frontend/src/components/PublishBatchResult.tsx` |
| Backend 결과 대기 | 30분 (`PUBLISH_RESULT_TIMEOUT`), 만료 확인 1분 주기 | 기한 뒤 묶음 FAILED, 티켓은 `RESULT_TIMEOUT` 결과 모름 | `PublishBatchExpiry`, `PublishService.expireStale` |
| Backend → Agent 접수 | 연결 3초, 응답 5초 | 응답 시간 초과는 "받았을 수 있음"으로 보고 결과를 기다림 | `AgentProperties`, `LivePublishGateway.submit` |
| Agent 발행 묶음 전체 | 없음 | 가장 느린 단계가 끝날 때까지 RUNNING | `agent/runner.py` `run_publish_batch` |
| Agent → MCP 도구 호출 | **없음** (아래 설명) | MCP 서버가 응답하지 않으면 끝없이 기다림 | `agent/runner.py` `_mcp_client` |
| MCP → 외부 API | 요청 1회 20초. GitHub 댓글 확인 최대 10페이지, Notion 429 재시도 | 도구 하나가 여러 요청 시간만큼 길어짐 | `business-mcp/common.py`, `integrations/*/api.py` |
| Agent → RAG MCP 도구 목록 조회 | **없음** | rag-mcp가 응답 없이 멈추면 재대조 전에서 멈춤 | `agent/runner.py` `make_issue_tools` (`client.get_tools`) |
| 재대조 색인 갱신 | `RAG_SYNC_TIMEOUT_SECONDS` (기본 30초) | 기존 색인으로 진행 | `agent/recheck.py` |
| 재대조 판단 | 티켓당 `DEDUPE_TIMEOUT_SECONDS` (기본 30초), LLM 호출 1회 `LLM_TIMEOUT_SECONDS` (기본 120초) | 판단 없이 기존 처리 유형으로 발행 | `agent/dedupe.py` `judge_ticket`, `agent/llm.py` |
| 발행 후 색인 (`index_issue`) | **없음** | 외부 쓰기는 끝났는데 묶음이 RUNNING으로 남고, 30분 뒤 실제로 발행된 티켓이 `RESULT_TIMEOUT` 결과 모름이 됨 | `agent/runner.py` `run_publish_batch` (`tools.index`) |
| Agent 재시작 | 발행 묶음 기록이 메모리에만 있음 | 처리 중이던 묶음이 404가 되고 Backend는 결과 대기 기한까지 기다림 | `agent/main.py` `_publish_jobs` |
| Agent → Backend 결과 콜백 | 요청 10초, 3회 재시도 | 실패해도 Backend 조회(5초 주기)로 결과를 받음 | `agent/reporter.py` |

### MCP 도구 호출에 대기 상한이 없는 이유

코드 기준 (mcp 1.30.0, langchain-mcp-adapters 0.3.2):

- Agent는 MCP 연결에 `timeout`, `sse_read_timeout`, `session_kwargs`를 넘기지 않는다.
- 어댑터는 응답 스트림 읽기 시간 초과를 기본 300초로 두지만, mcp 클라이언트의 `_handle_sse_response`는 스트림 읽기 예외를 `logger.debug("SSE stream ended")`로 삼킨다. 이벤트 ID가 없으면 재연결하지 않고 응답 없이 스트림만 끝난다.
- `ClientSession`의 `read_timeout_seconds` 기본값은 `None`이라 호출한 쪽은 응답을 끝없이 기다린다.

즉 300초가 지나도 "시간 초과로 끝나는" 것이 아니라 "스트림이 말없이 끊기고 계속 기다리는" 것이다. `ClientSession`은 `read_timeout_seconds`를 받고 어댑터는 `session_kwargs`로 넘길 수 있으므로, #302에서 이 값으로 도구 호출 대기 상한을 둘 수 있다.

## #302 수정 후

| 단계 | 수정 전 (#299) | 수정 후 (#302) | 위치 |
|---|---|---|---|
| Agent → MCP 요청 하나 (도구 호출, 도구 목록 조회) | 상한 없음 | `MCP_CALL_TIMEOUT_SECONDS`(기본 90초)를 `ClientSession` `read_timeout_seconds`와 `sse_read_timeout`으로 건다. `sse_read_timeout`은 연결을 닫을 때의 세션 종료 요청(DELETE) 상한이기도 해서, 두지 않으면 멈춘 서버에서 정리가 300초 늘어진다. 연결·전송은 `MCP_HTTP_TIMEOUT_SECONDS`(기본 10초). 분석 경로(맥락 조회, 중복 판단, 권한·연결 확인)에도 같이 걸린다 | `agent/runner.py` `_mcp_client` |
| Agent → RAG MCP 도구 목록 조회 | 상한 없음 | 위 응답 대기 상한 안에 끝나고, 넘으면 재대조 없이 진행 | `agent/runner.py` `make_issue_tools` |
| Agent 발행 묶음 전체 | 상한 없음 | `PUBLISH_DEADLINE_SECONDS`(기본 600초). 재대조는 남은 마감의 절반 안에서만 하고 넘으면 재대조 없이 발행. 발행이 마감에 걸리면 쓰기 요청을 보낸 티켓은 결과 모름(`RESULT_UNKNOWN`), 보내지 않은 티켓(시작 전, 쓰기 전 확인 조회 중)은 재시도 가능 실패(`PUBLISH_DEADLINE`). MCP 서버가 멈춘 경우 연결 정리에 최대 90초가 더 걸린다 | `agent/runner.py` `run_publish_batch`, `agent/batch_publish.py` `publish_batch` |
| 발행 후 색인 | 상한 없음 | `RAG_SYNC_TIMEOUT_SECONDS`와 남은 마감 중 짧은 쪽. 넘어도 발행 결과는 그대로 | `agent/runner.py` `run_publish_batch` |
| 발행 MCP 도구 목록을 못 가져옴 | 묶음 FAILED, 항목 없음 → Backend가 모든 티켓을 결과 모름(`RESULT_MISSING`)으로 처리 | 아무것도 보내지 않았으므로 모든 티켓을 재시도 가능 실패(`MCP_UNAVAILABLE`)로 보고 | `agent/runner.py`, `agent/batch_publish.py` `unsent_batch` |
| Agent 재시작 (묶음 404) | "아직 결과 없음"으로 보고 결과 대기 기한(30분)까지 RUNNING | 접수 뒤 유예 시간(`PUBLISH_LOST_GRACE`, 기본 1분)이 지난 묶음은 다음 조회 주기(5초)에 `RESULT_LOST` 결과 모름으로 끝냄. Agent가 워커 1개로 돈다는 전제다 (워커를 늘리면 다른 워커의 404로 정상 묶음이 끝난다) | `LivePublishGateway.status`, `PublishService.reconcileRunning` |
| Backend 결과 대기 기한 | 30분 | 15분 (Agent 마감 10분보다 길게 두는 마지막 안전망) | `application.yml` `app.publish.result-timeout` |
| 화면 결과 폴링 | 스피너만 표시 | 경과 시간 표시, 2분이 지나면 "화면을 닫아도 발행은 계속되며 늦어도 약 16분 뒤에는 정리된다"는 안내 (경과 시간은 묶음 생성 시각 기준) | `PublishBatchResult.tsx`, `batchPublish.ts` |

#302 전후 비교 (계산값, Agent 재시작은 Docker 측정값 포함)

| 시나리오 | 수정 전 | 수정 후 |
|---|---|---|
| 발행 중 Agent 재시작 | 결과 대기 기한까지, 최대 31분 | 유예 시간 1분 + 조회 주기 5초. Docker 측정 **60초** (`pause agent` → 발행 → `kill agent` → `up agent`, `DRY_RUN=true`. 묶음 FAILED, 티켓 `RESULT_LOST` 결과 모름·재시도 가능) |
| MCP 서버 무응답 | 끝나지 않음 (Backend 기한 30분 뒤 결과 모름) | 도구 호출 하나 최대 90초, 묶음 최대 600초 + 연결 정리 90초. MCP_DOCKER_PLACEHOLDER |
| rag-mcp 무응답 | 끝나지 않음 (Backend 기한 30분 뒤 결과 모름, 발행 후 색인이면 실제로 발행된 티켓도 결과 모름) | 도구 목록 조회 최대 90초 뒤 재대조 없이 발행, 발행 후 색인은 최대 30초 |

## 재현 테스트

#299에서 고정한 현재 동작 테스트를 #302에서 기대 동작으로 바꿨다.

Agent (`cd agent && .venv/bin/python -m pytest -q tests/test_publish_hang.py tests/test_publish_mcp_http.py tests/test_publish_result_api.py`)

| 테스트 | 확인하는 동작 |
|---|---|
| `test_hung_external_write_ends_as_unknown_within_limit` | 외부 쓰기가 응답하지 않아도 도구 호출 상한이나 묶음 마감 안에 결과 모름으로 끝난다. 이슈 생성 요청은 이미 외부로 나갔다 (실제 MCP HTTP) |
| `test_frozen_mcp_server_ends_within_limits_including_session_close` | MCP 서버 프로세스가 통째로 멈춰도(SIGSTOP) 연결 정리까지 상한 안에 끝난다. `sse_read_timeout`이 없으면 300초가 더 걸린다 (실제 MCP HTTP) |
| `test_mcp_client_applies_configured_timeouts` | MCP 연결·응답 대기 상한을 설정값으로 건다. 전체 재색인은 상한을 두지 않는다 |
| `test_publish_deadline_leaves_inflight_unknown_and_unsent_retryable` | 마감에 걸리면 쓰기를 보낸 티켓은 결과 모름(`RESULT_UNKNOWN`), 시작 전 티켓은 재시도 가능 실패(`PUBLISH_DEADLINE`)다 |
| `test_deadline_during_pre_write_lookup_is_unsent_not_unknown` | 쓰기 전 확인 조회 중 마감에 걸리면 보내지 않은 것이므로 재시도 가능 실패다 |
| `test_recheck_stuck_at_deadline_still_leaves_time_to_publish` | 재대조가 멈춰도 남은 마감의 절반에서 끊고 발행한다 |
| `test_unreachable_business_mcp_reports_every_ticket_unsent` | 발행 MCP 도구를 못 가져오면 모든 티켓을 재시도 가능 실패로 보고한다 |
| `test_hung_rag_tool_listing_is_bounded` | 응답 없는 rag-mcp의 도구 목록 조회가 상한 안에 끝나고 재대조 없이 진행한다 |
| `test_hung_index_after_publish_does_not_hold_result` | 발행 후 색인이 멈춰도 발행 결과를 시간 안에 돌려준다 |
| `test_restarted_agent_cannot_tell_lost_batch_from_unknown_batch` | 재시작 후 처리 중이던 묶음과 처음 보는 묶음이 같은 404다 (Backend가 유예 시간으로 구분한다) |
| `test_result_stays_readable_after_result_callback_fails` | 콜백이 재시도까지 실패한 뒤에도 GET으로 결과를 받을 수 있다 |
| `test_refused_rag_mcp_connection_does_not_hold_publish_batch` | rag-mcp 연결이 거부되면 재대조만 건너뛰고 바로 발행한다 |

`test_publish_mcp_http.py`는 Business MCP 가상환경이 있어야 실행된다. 없으면 건너뛴다 (`cd business-mcp && uv venv --python 3.12 && uv pip sync --python .venv/bin/python requirements-dev.txt`).

Backend (`cd backend && ./gradlew test --tests '*PublishBatchTest' --tests '*LivePublishGatewayTest'`)

| 테스트 | 확인하는 동작 |
|---|---|
| `Agent가_묶음을_모르면_유예_시간_뒤_결과_모름으로_바로_끝낸다` | 404 묶음은 유예 시간 동안 기다렸다가 `RESULT_LOST` 결과 모름(재시도 가능)으로 끝낸다 |
| `Agent가_결과를_주지_않으면_결과_대기_기한_뒤_결과_모름으로_끝낸다` | 처리 중이라는데 결과가 오지 않으면 기한 뒤 `RESULT_TIMEOUT` 결과 모름으로 끝낸다 |
| `콜백_실패_후_GET으로_완료_결과만_복구한다` | 404를 "아직 결과 없음"과 구분해 `PublishBatchLostException`으로 알린다 |

Frontend (`cd frontend && node --test tests/batchPublish.test.mjs tests/publishFailure.test.mjs`): 경과 시간(묶음 생성 시각 기준) 표시, 2분 뒤 안내, `RESULT_LOST`·`PUBLISH_DEADLINE` 안내 문구와 재시도 가능 여부.

## Docker 시나리오

`compose.full.yml` 전체 구성에서 확인한다. 시나리오마다 확인한 티켓 1건을 발행하고 화면과 DB 상태를 기록한다. 측정 중에는 Mac 절전과 Docker Desktop 리소스 절약 모드를 끄고, Agent·business-mcp 로그 시각을 함께 남긴다.

| 시나리오 | 방법 | 외부 쓰기 |
|---|---|---|
| Agent 재시작 | `docker compose -f compose.full.yml pause agent` → 발행 → `kill agent` → `up -d agent` | 없음 (`DRY_RUN=true` 가능) |
| MCP 무응답 | `pause business-mcp` → 발행 → 측정 후 `unpause` | 있음 (`DRY_RUN=false`, 테스트 저장소만) |
| rag-mcp 중단 (연결 거부) | `stop rag-mcp` → 발행 | `DRY_RUN`에 따름 |
| rag-mcp 무응답 | `pause rag-mcp` → 발행 → 측정 후 `unpause` | `DRY_RUN`에 따름 |
| 분석 흐름 | 분석 중 `kill agent` → `up -d agent` | 없음 |

티켓 상태는 아래처럼 확인한다.

```bash
docker compose -f compose.full.yml exec -T postgres psql -U meettoticket -d meettoticket \
  -c "select title, ticket_status, publish_error_code, publish_outcome_unknown from task_items order by task_item_id desc limit 5"
docker compose -f compose.full.yml exec -T postgres psql -U meettoticket -d meettoticket \
  -c "select publish_batch_id, status, created_at, finished_at from publish_batches order by created_at desc limit 5"
```

## 측정 결과 (#299, 수정 전)

2026-10-24, 브랜치 `test/299-publish-hang-repro`(develop `4b8b96a` 기준)로 `compose.full.yml`을 다시 빌드해 측정했다. 테스트 회의록은 함주헌 담당 GitHub 작업 3건이며, 회의별 발행(`meetingId`, `ticketIds`)으로 시나리오마다 티켓 1건만 발행했다.

**#302 전후 비교 기준은 계산값으로 둔다.** Agent 재시작 시 묶음이 끝나기까지 현재 기준은 결과 대기 기한 30분 + 만료 확인 주기 최대 1분이며, Backend 테스트(`Agent가_묶음을_모르면_결과_대기_기한까지_발행_중으로_남는다`)로 고정했다.

| 시나리오 | 발행 요청 응답 | 묶음이 끝나기까지 | 최종 묶음 상태 | 티켓 상태 | 비고 |
|---|---|---|---|---|---|
| Agent 재시작 (`DRY_RUN=true`) | 5.04초 (Backend 접수 응답 대기 5초 초과 후 202) | 결과 대기 기한까지 (계산값 최대 31분) | FAILED | FAILED, `RESULT_TIMEOUT`, 결과 모름, 재시도 가능 | 그동안 같은 회의의 다른 티켓을 발행할 수 없었다 |
| rag-mcp 중단, 연결 거부 (`DRY_RUN=true`) | 즉시 202 | 0.8초 | SUCCEEDED | SKIPPED | 재대조만 건너뛰고 바로 끝났다 |
| 분석 중 Agent 재시작 | 즉시 202 | 19초 | 분석 FAILED | 티켓 없음 | `AGENT_TASK_UNAVAILABLE`로 바로 끝났다. 새 실행을 만들지 않았다 |
| MCP 무응답 | | | | | 코드 근거와 자동 테스트로 확인 (Docker 실측은 다시 한다) |
| rag-mcp 무응답 | | | | | 미측정 |

### 확인한 점

- **MCP 도구 호출에는 대기 상한이 없다.** 측정이 아니라 코드 근거다 (위 "MCP 도구 호출에 대기 상한이 없는 이유"). 묶음 마감(#302)이 없으면 MCP나 외부 API가 멈춘 만큼 화면도 멈춘다.
- **응답이 없는 rag-mcp도 묶음을 멈춘다.** 도구 목록 조회와 발행 후 색인에 상한이 없다. 발행 후 색인에서 멈추면 외부 쓰기는 끝났는데 30분 뒤 결과 모름으로 처리되어, 담당자가 이미 발행된 티켓을 다시 확인해야 한다. 연결 거부(꺼짐)는 바로 넘어간다.
- **마감으로 끊어도 쓰기는 이미 나갔을 수 있다.** 외부 쓰기 무응답 테스트에서 이슈 생성 요청은 응답 전에 이미 외부로 나갔다. #302에서 마감으로 끊을 때 이미 보낸 쓰기는 재시도 가능 실패가 아니라 결과 모름이어야 한다.
- **발행 중인 티켓 하나가 회의 전체 발행을 막는다.** 같은 회의에 PUBLISHING 티켓이 있으면 회의별 발행이 `TICKET_STATUS_CONFLICT`(409)로 거절된다 (`PublishService.startMeeting`). Agent 재시작 시나리오에서는 묶음이 끝날 때까지 그 회의를 발행할 수 없었다.
- **분석은 Agent 재시작을 바로 알아채고, 발행은 알아채지 못한다.** 같은 404를 분석(`LiveAgentGateway` → `AnalysisCoordinator`)은 "Agent가 실행을 잃음"으로 보고 즉시 실패시키지만, 발행(`LivePublishGateway.status`)은 "아직 결과 없음"으로 보고 기한까지 기다린다. #302는 분석의 처리 방식을 따르면 된다. 단, 발행은 쓰기가 나갔을 수 있으므로 실패가 아니라 결과 모름으로 끝내야 한다.
- **접수 응답 시간 초과는 화면에서 성공처럼 보인다.** Agent가 응답하지 않아도 Backend는 5초 뒤 "받았을 수 있음"으로 보고 202를 돌려주므로, 사용자는 발행이 접수된 것으로 본다.

### 측정의 한계

- MCP 무응답과 rag-mcp 무응답은 Docker 실측값이 없다. MCP 호출 도중 멈추는 경우는 시점을 맞추기 어려워 자동 테스트(`test_hung_external_write_holds_publish_batch_without_agent_deadline`)와 코드 근거로 확인했다. Docker로 다시 잴 때는 business-mcp·Agent 로그 시각을 함께 남긴다.
