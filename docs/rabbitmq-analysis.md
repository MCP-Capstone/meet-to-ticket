# RabbitMQ 분석 접수 (#75)

## 실행

```bash
# 저장소 루트
# .env는 Compose에 적용된다. Spring을 로컬 실행하면 변경한 값을 같은 환경변수로 별도 전달한다.
docker compose up -d postgres rabbitmq
cd backend
./gradlew build
./gradlew bootRun --args='--spring.profiles.active=demo'
```

관리 화면: http://localhost:15672 (기본 계정 `meettoticket` / `meettoticket`).
AMQP 5672, 관리 화면 15672. 기본 demo는 MOCK Agent이지만 RabbitMQ는 실제로 사용한다.
실제 Agent 연결은 `AGENT_MODE=live`로 실행한다. 기존 8080 백엔드가 실행 중이면 재시작해야 새 코드가 적용된다.

| 환경변수 | 기본값 |
|---|---|
| RABBITMQ_HOST | localhost |
| RABBITMQ_PORT | 5672 |
| RABBITMQ_USERNAME | meettoticket |
| RABBITMQ_PASSWORD | meettoticket |
| RABBITMQ_VHOST | / |

자격 증명과 vhost 기본값은 Compose와 같다. Compose 볼륨의 기존 자격 증명은 환경변수 변경만으로 재설정되지 않는다.

## 구성

```text
POST /api/meetings/{id}/analysis-tasks
  → AnalysisCoordinator.start: 작업 + analysis_dispatches(outbox) 함께 저장
  → DB 커밋 후 taskId 발행 → HTTP 202
  → analysis.tasks(exchange) / analysis.submit(routing key)
  → analysis.tasks.queue
  → AnalysisTaskListener: 저장된 요청 스냅샷으로 Agent POST /runs
  → 기존 GET /runs 폴링 → 기존 결과 저장/프론트 조회

확실한 접수 실패 4회
  → 작업 FAILED + 접수 기록 EXHAUSTED를 함께 커밋
  → reject(requeue=false)
  → analysis.tasks.dlx / analysis.failed → analysis.tasks.dlq
```

큐/교환기는 durable, 메시지는 persistent이다. 메시지 본문과 messageId는 taskId이며 회의 원문을 메시지에 복제하지 않는다.
Publisher confirm와 mandatory return을 확인한다. 발행 미확인/연결 장애 시 기록을 유지하고 5초 주기의 outbox relay로 재발행한다.
작업과 outbox는 같은 트랜잭션이므로 롤백된 요청은 발행되지 않는다. 브로커 확인을 기다리는 최대 시간은 5초이고 연결 제한은 3초다.

## 접수 정책

| 상황 | 처리 |
|---|---|
| 연결 거부 등 확실한 미접수 | 최초 호출 이후 2초·4초·8초 대기 후 3회 재시도 (총 POST 최대 4회) |
| 202 또는 기존 작업 409 | ACCEPTED, 이후 조회만 수행 |
| 읽기 시간 초과·5xx·불완전한 응답 등 접수 불명 | UNCERTAIN, 재POST 없이 기존 폴링으로 확인 |
| 확실한 미접수 4회 | FAILED + EXHAUSTED 커밋 후 DLQ |
| QUEUED/SUBMITTING | 폴링의 Agent GET을 보류해 조기 404 실패 방지 |
| 중복 메시지·완료 작업 | DB 잠금으로 최초 수신자만 SUBMITTING 전환, 나머지는 POST 생략 |

재시도 간격의 합은 14초이며, HTTP 처리 시간은 추가된다. 네트워크/대기 동안 DB 트랜잭션을 유지하지 않는다.
기존 task timeout(기본 10분), 결과 저장 및 프론트 API는 유지한다. DLQ는 자동 재실행하지 않는다.

프로세스가 SUBMITTING 중 비정상 종료되면 Agent 접수 여부를 확신할 수 없다. 해당 taskId를 자동 재POST하지 않고 기존 task timeout으로 종료한다.
현재 Agent는 완료 taskId를 다시 POST하면 재실행하고 실행 기록도 메모리이므로, 재시작 후 404만 보고 재접수하면 GitHub 이슈가 중복될 수 있다.
Agent 영속 멱등성 작업(#66)과 별개로 이 PR은 보수적으로 중복 실행을 방지한다. DLQ 수동 재실행 전 GitHub 반영 여부를 확인한다.

## 협업 범위

기존 analysis 프로덕션 파일은 `AnalysisCoordinator.start`와 관련 import만 변경했다.
`pollPending`, `AnalysisStore`, `AnalysisTask`, 기존 컨트롤러와 프론트는 변경하지 않는다.
큐 상태는 별도 `queue` 패키지·테이블에서 관리한다. Swagger 접수 설명은 별도 OperationCustomizer로 갱신한다.
임나윤 담당 InternalTaskController와 AnalysisStore 단계 갱신 메서드는 이 변경과 독립적으로 추가할 수 있다.

## 검증 및 증빙

- `cd backend && ./gradlew build`
- 실제 RabbitMQ + 별도 PostgreSQL + 실제 Agent 실행 코드로 업로드→큐→분석→결과 1건 저장 확인.
- 검증의 LLM·GitHub 발행은 고정 모의 응답이며 실제 GitHub 이슈를 만들지 않았다. 실제 외부 서비스까지의 정상 demo 확인은 별도로 필요하다.
- 검증용 Agent만 중지: HTTP 202(0.018초) → 재시도 로그 3회(2/4/8초) → FAILED(약 14.24초) → 동일 taskId DLQ 보관.
- 완료된 taskId를 실제 큐에 다시 발행해 접수 횟수 1회와 COMPLETED 유지 확인.
- [증빙 파일](evidence/75-rabbitmq/): 정상·실패 JSON, 재시도 로그, 중복 수신 확인, 관리 화면 캡처와 PDF.
- 검증용 서버 포트는 18080/18001, PostgreSQL은 15432였다. 제품의 기본 포트는 변경하지 않았다.

## PR 참고 사항

패키지 업데이트 필요: cd backend && ./gradlew build

[Spring AMQP: 큐 구성](https://docs.spring.io/spring-amqp/reference/amqp/broker-configuration.html)
· [발행 확인과 mandatory return](https://docs.spring.io/spring-amqp/reference/amqp/template.html)
