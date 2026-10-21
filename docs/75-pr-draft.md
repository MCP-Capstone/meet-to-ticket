## 관련 이슈
Closes #75

## 변경 사항
- 분석 요청을 RabbitMQ에 접수해 Agent 중지 시에도 202 반환
- 확실한 미접수만 최초 호출 이후 2초·4초·8초에 재시도하고 최종 실패는 FAILED/DLQ 처리
- 영속 접수 기록과 DB 잠금으로 중복 수신 방지, 접수 불명은 재POST 없이 기존 폴링 유지
- 작업/outbox를 함께 저장하고 브로커 발행 미확인은 확인 후 재발행
- 기존 analysis 파일은 AnalysisCoordinator.start와 import만 수정; AnalysisStore·기존 컨트롤러·프론트 유지

## 테스트
- `cd backend && ./gradlew build`
- 실제 RabbitMQ·PostgreSQL·Agent 코드에서 업로드→큐→결과 저장 확인 (LLM·GitHub 모의)
- Agent 중지: 202(0.018초) → 2초·4초·8초 재시도 → FAILED(약 14.24초) 및 동일 taskId DLQ 확인
- 완료 taskId 실제 큐 재발행 후 접수 횟수 1회·COMPLETED 유지 확인
- 롤백 시 작업/outbox 동시 취소, 큐 대기 중 폴링 보호, 접수 불명 재POST 방지 자동 테스트
- 캡처·PDF: docs/evidence/75-rabbitmq

## 체크리스트
- [ ] 커밋 메시지가 `type(scope): subject` 컨벤션을 따른다
- [x] 로컬에서 동작을 확인했다
- [x] 필요한 경우 문서를 갱신했다
- [x] 패키지 변경 시 실행할 명령을 아래에 적었다

## 참고 사항
패키지 업데이트 필요: cd backend && ./gradlew build

- 실제 LLM·GitHub를 사용하는 전체 demo 검증은 별도로 필요하다.
- 접수 중 프로세스 종료는 중복 이슈 방지를 위해 자동 재접수하지 않고 기존 timeout으로 종료한다.
- Agent의 완료 taskId 영속 멱등성은 별도 이슈 #66이다.
- develop 대상 PR, 최종 병합은 PM 담당.
