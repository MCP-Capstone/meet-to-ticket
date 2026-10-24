# 발행 실패·재시도 복구

관련 이슈: #291. 기존 #282·#283·#284의 작업 내용과 완료 조건을 통합한다.

## 재시도 제한

실패 티켓의 retryable이 false면 API는 409 PUBLISH_RETRY_NOT_ALLOWED를 반환하고 화면은 재시도 버튼을 비활성화한다. 원인·프로젝트 설정을 해결한 뒤 수정·재승인 경로를 사용한다.

Notion·Slack의 결과 불명은 기존과 같이 수동 확인 결과를 먼저 남겨야 한다. 단순 수정으로 이 확인 절차를 우회하지 않는다.

## 알림 댓글만 재전송

- 대상은 담당자 본인의 GitHub UPDATED + COMMENT_FAILED 티켓이다.
- `POST /api/tickets/{ticketId}/retry-comment`는 202와 batchId를 반환한다. 담당자·상태·행 잠금을 검증한다.
- Backend는 실제 갱신한 이슈 번호와 retryCommentOnly=true를 Agent에 전달한다.
- Agent는 대상이 있는 GitHub UPDATE 요청만 수용하고 LLM·중복 재검수·본문 PATCH를 실행하지 않는다.
- 댓글의 meettoticket:update-comment 표식을 먼저 확인한다. 이미 있으면 쓰지 않고, 조회가 불완전하면 전송하지 않는다.
- 댓글 재전송의 접수 실패·결과 누락·응답 유실에도 본문 갱신 성공과 기존 이슈 링크를 보존한다.

기존 댓글에 표식이 없는 경우의 자동 반영 확인이나 장시간 실행 경쟁 조건까지 보장하지는 않는다. DB 멱등성·예약 상태·복구 워커는 #263 범위다.

## 전송 단계별 실패 분류

| 단계 | 처리 |
|---|---|
| HTTP 연결·풀 확보 실패로 미전송이 확인됨 | MCP_UNAVAILABLE, 재시도 가능 |
| 쓰기 요청의 읽기·쓰기·연결 종료 오류 | RESULT_UNKNOWN / UNKNOWN, 대상별 확인 절차 |
| 쓰기 도구 호출 중 전송 단계를 확인할 수 없음 | 보수적으로 UNKNOWN |
| Slack 게시 후 링크 조회 실패 | 확인 없이 메시지를 다시 보내지 않도록 UNKNOWN |
| Notion 생성 후 추가 본문 기록 실패 | 확인 없이 페이지를 다시 만들지 않도록 UNKNOWN |

MCP 오류가 문자열로 전달돼도 RESULT_UNKNOWN 태그를 유지해 Backend의 outcomeUnknown까지 반영한다.

## 테스트

- Agent: `.venv/bin/python -m pytest -q`
- Business MCP: `.venv/bin/python -m pytest -q`
- Backend: `./gradlew test`
- Frontend: `node --test tests/*.test.mjs`, `npm run build`, `npm run lint`

실제 로컬 HTTP MCP 연동 테스트는 Agent adapter → Business MCP 도구/API 래퍼 → httpx.MockTransport를 사용한다. Agent와 Business MCP 가상환경을 각각 준비하거나 BUSINESS_MCP_PYTHON으로 서버 인터프리터를 지정한다. 실제 토큰이나 외부 발행은 사용하지 않는다.

주요 검증은 권한·잘못된 상태·동시 요청 차단, 본문 PATCH 1회, 댓글 반영 후 응답 유실 재시도 시 댓글 1개 유지, 표식 조회 불완전 시 전송 차단, 세 발행 대상의 전송 전·후 분류다.

모델·프롬프트·중복 판정 규칙·공식 평가체계는 변경하지 않는다.
