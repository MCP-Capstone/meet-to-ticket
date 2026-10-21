# PR #81 리뷰 반영 내용

1. SUBMITTING 고착
   - claim·POST 시도 시 갱신 시각을 저장하고 기본 2분간 갱신이 없으면 기존 폴링에서 UNCERTAIN으로 복구합니다.
   - Agent GET으로 기존 진행·결과를 회수하고, 재POST하지 않아 중복 이슈를 방지합니다.
   - 오래된 행의 갱신 시각이 NULL이어도 복구합니다. 최근 claim은 보호합니다.

2. DB 예외 반복 재전달
   - 리스너 전체를 예외 경계로 감싸 request/terminal 등 모든 위치의 예외를 처리합니다.
   - DB 접근·트랜잭션 오류는 개별 DB 호출만 2초·4초·8초 간격으로 최대 4회 시도하고, 최종 실패는 reject하여 DLQ로 보냅니다.
   - 없는 작업·잘못된 작업 번호는 즉시 DLQ로 보냅니다. default-requeue-rejected=false도 설정했습니다.
   - Agent POST 성공 후 DB 저장 오류는 DB 저장만 재시도하며 Agent를 재호출하지 않습니다.
   - DB 장애 중 기록할 수 없는 FAILED는 별도 durable 실패 기록 큐에 보관하고, DB 복구 후 커밋한 뒤 ACK합니다. DLQ 원본과 이미 종료된 결과는 보존합니다.

검증: 전체 빌드 및 테스트 72개 통과. 이 중 3개는 전용 vhost에서 실제 RabbitMQ의 DLQ 분리, DB 복구 후 실패 상태 기록, 후속 작업 처리, POST 중복 방지를 검증했습니다.
DB 예외와 Agent 응답은 주입했으며 대기 함수는 모의 처리했습니다. 실행 결과는 docs/evidence/81-review에 있습니다.

이번 수정에서 기존 analysis 프로덕션 코드, Agent 및 프론트는 수정하지 않았습니다.
패키지 업데이트 필요: cd backend && ./gradlew build
