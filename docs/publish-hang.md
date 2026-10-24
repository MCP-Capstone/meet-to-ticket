# 발행 멈춤·무한대기 재현

관련 이슈: #299. 시간 상한과 결과 유실 처리는 #302에서 한다.

발행 버튼을 누른 뒤 화면이 "발행 중"에서 오래 멈출 수 있는 지점을 단계별로 정리하고, 재현 테스트와 실측 결과를 남긴다. #302 수정 후 같은 조건으로 다시 측정해 전후를 비교한다.

## 발행 경로와 시간 상한

```
화면 ──2초 폴링──▶ Backend ──POST /publish──▶ Agent ──MCP 도구 호출──▶ Business MCP ──▶ GitHub / Notion / Slack
                 ◀─5초마다 GET /publish/{id}─┘      ◀── 결과 콜백 ──┘
```

| 단계 | 현재 시간 상한 | 끝나지 않을 때 | 위치 |
|---|---|---|---|
| 화면 결과 폴링 | 없음. RUNNING이면 2초마다 계속 조회 | 스피너만 표시, 경과 시간·안내 없음 | `frontend/src/components/PublishBatchResult.tsx` |
| Backend 결과 대기 | 30분 (`PUBLISH_RESULT_TIMEOUT`) | 기한 뒤 묶음 FAILED, 티켓은 `RESULT_TIMEOUT` 결과 모름 | `PublishBatchExpiry`, `PublishService.expireStale` |
| Backend → Agent 접수 | 연결 3초, 응답 5초 | 응답 시간 초과는 "받았을 수 있음"으로 보고 결과를 기다림 | `AgentProperties`, `LivePublishGateway.submit` |
| Agent 발행 묶음 전체 | 없음 | 가장 느린 도구 호출이 끝날 때까지 RUNNING | `agent/runner.py` `run_publish_batch` |
| Agent → MCP 도구 호출 | 미설정. 라이브러리 기본 연결 30초, 응답 대기 5분 | 도구 하나가 최대 5분 대기 | `agent/runner.py` `_mcp_client` |
| MCP → 외부 API | 요청 1회 20초. GitHub 댓글 확인 최대 10페이지, Notion 429 재시도 | 도구 하나가 여러 요청 시간만큼 길어짐 | `business-mcp/common.py`, `integrations/*/api.py` |
| Agent 재시작 | 발행 묶음 기록이 메모리에만 있음 | 처리 중이던 묶음이 404가 되고 Backend는 결과 대기 기한까지 기다림 | `agent/main.py` `_publish_jobs` |
| Agent → Backend 결과 콜백 | 요청 10초, 3회 재시도 | 실패해도 Backend 조회(5초 주기)로 결과를 받음 | `agent/reporter.py` |
| 재대조 색인 갱신 | `RAG_SYNC_TIMEOUT_SECONDS` (기본 30초) | 기존 색인으로 진행 | `agent/recheck.py` |

## 재현 테스트

Agent (`cd agent && .venv/bin/python -m pytest -q tests/test_publish_hang.py tests/test_publish_mcp_http.py`)

| 테스트 | 확인하는 현재 동작 |
|---|---|
| `test_hung_external_write_holds_publish_batch_without_agent_deadline` | 외부 쓰기가 응답하지 않으면 묶음이 끝나지 않는다. 이슈 생성 요청은 이미 외부로 나갔다 |
| `test_mcp_client_uses_library_default_timeouts` | MCP 연결 시간 상한이 라이브러리 기본값(30초, 5분)이다 |
| `test_restarted_agent_cannot_tell_lost_batch_from_unknown_batch` | 재시작 후 처리 중이던 묶음과 처음 보는 묶음이 같은 404다 |
| `test_failed_result_callback_still_leaves_result_for_backend_polling` | 콜백이 실패해도 GET으로 결과를 받을 수 있다 (멈추지 않는 경로) |
| `test_unreachable_rag_mcp_does_not_hold_publish_batch` | rag-mcp가 꺼져 있어도 재대조만 건너뛰고 바로 발행한다 (멈추지 않는 경로) |

`test_publish_mcp_http.py`는 Business MCP 가상환경이 있어야 실행된다. 없으면 건너뛴다 (`cd business-mcp && uv venv --python 3.12 && uv pip sync --python .venv/bin/python requirements-dev.txt`).

Backend (`cd backend && ./gradlew test --tests '*PublishBatchTest'`)

| 테스트 | 확인하는 현재 동작 |
|---|---|
| `Agent가_묶음을_모르면_결과_대기_기한까지_발행_중으로_남는다` | Agent 조회가 결과 없음이면 기한(30분)이 지나야 `RESULT_TIMEOUT` 결과 모름으로 끝난다 |

#302에서 바꿀 동작을 고정한 테스트는 docstring이나 주석에 "#302에서 바꿀 현재 동작"이라고 적었다. 시간 상한을 넣으면 기대 동작으로 바꾼다.

## Docker 시나리오

`compose.full.yml` 전체 구성에서 확인한다. 시나리오마다 확인한 티켓 1건을 발행하고 화면과 DB 상태를 기록한다.

| 시나리오 | 방법 | 외부 쓰기 |
|---|---|---|
| Agent 재시작 | `docker compose -f compose.full.yml pause agent` → 발행 → `restart agent` | 없음 (`DRY_RUN=true` 가능) |
| MCP 무응답 | `pause business-mcp` → 발행 → 측정 후 `unpause` | 있음 (`DRY_RUN=false`, 테스트 저장소만) |
| rag-mcp 중단 | `stop rag-mcp` → 발행 | `DRY_RUN`에 따름 |
| 분석 흐름 | 분석 중 `restart agent` | 없음 |

티켓 상태는 아래처럼 확인한다.

```bash
docker compose -f compose.full.yml exec -T postgres psql -U meettoticket -d meettoticket \
  -c "select title, ticket_status, publish_error_code, publish_outcome_unknown from task_items order by task_item_id desc limit 5"
docker compose -f compose.full.yml exec -T postgres psql -U meettoticket -d meettoticket \
  -c "select publish_batch_id, status, created_at, finished_at from publish_batches order by created_at desc limit 5"
```

## 측정 결과

| 시나리오 | 화면이 끝나기까지 | 최종 묶음 상태 | 티켓 상태 | 비고 |
|---|---|---|---|---|
| Agent 재시작 | | | | |
| MCP 무응답 | | | | |
| rag-mcp 중단 | | | | |
| 분석 중 Agent 재시작 | | | | |
