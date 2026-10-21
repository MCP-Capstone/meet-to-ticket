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
  → analysis.tasks.dlx / analysis.failed → analysis.tasks.dlq (원본 보존)
                                       → analysis.tasks.failure-updates (실패 기록 복사본)
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
| QUEUED/최근 SUBMITTING | 폴링의 Agent GET을 보류해 조기 404 실패 방지 |
| 2분간 갱신 없는 SUBMITTING | UNCERTAIN으로 전환 후 기존 Agent GET으로 확인. 재POST하지 않음 |
| DB 호출 실패 | 해당 DB 호출만 2초·4초·8초 후 재시도. 4회 실패 시 DLQ |
| 존재하지 않는 작업 | 즉시 DLQ. 예외 발생 위치와 관계없이 재큐잉하지 않음 |
| 중복 메시지·완료 작업 | DB 잠금으로 최초 수신자만 SUBMITTING 전환, 나머지는 POST 생략 |

재시도 간격의 합은 14초이며, HTTP 처리 시간은 추가된다. 네트워크/대기 동안 DB 트랜잭션을 유지하지 않는다.
기존 task timeout(기본 10분), 결과 저장 및 프론트 API는 유지한다. DLQ는 자동 재실행하지 않는다.

`claim`과 각 POST 직전 `attempted`에서 접수 갱신 시각(`analysis_dispatches.updated_at`)을 기록한다.
프로세스가 종료돼 마지막 갱신 후 기본 2분이 지나면 기존 폴링에서 DB 잠금으로 SUBMITTING → UNCERTAIN을 전환하고 Agent GET을 재개한다.
Agent가 이미 받았다면 진행·결과를 회수하고, Agent에 작업이 없다면 기존 실패 처리로 종료한다. 응답 불명은 기존 전체 timeout을 따른다.
기존 DB의 갱신 시각 없는 SUBMITTING 행도 조회로 복구한다. 환경변수 `ANALYSIS_SUBMITTING_TIMEOUT`으로 기본 2분을 조정할 수 있다.

Agent POST와 DB 재시도 범위를 분리했다. 특히 Agent가 이미 접수한 뒤 DB 저장이 실패해도 **DB 저장만** 재시도한다.
리스너 전체를 다시 실행하지 않고 `claim`, `request`, `terminal`, `attempted`, `finish` 각각의 DB 접근/트랜잭션 오류에만 최대 4회 시도한다.
조회 대상 없음과 다른 처리 오류는 리스너 전체의 예외 경계에서 reject(requeue=false)한다. 설정에서도 `default-requeue-rejected=false`로 무한 재전달을 방지한다.
`retry.enabled=false`는 전체 리스너를 반복해 Agent POST까지 중복 실행하지 않기 위한 설정이며, DB 재시도는 별도 코드에서 수행한다.

DB가 계속 꺼져 있으면 FAILED를 즉시 기록할 수 없으므로, DLX가 원본 DLQ와 `analysis.tasks.failure-updates`에 동시에 라우팅한다.
실패 기록용 복사본은 기본 5초 주기로 읽고 **DB 트랜잭션 커밋 후에만 ACK**한다. DB 오류 시 NACK/requeue 후 그 주기의 처리를 멈춰 지연 없는 반복을 막는다.
DB 복구 후 해당 작업을 FAILED로 기록하며, 이미 종료된 상태·결과·실패 사유는 덮어쓰지 않는다. DLQ 원본은 계속 보존되고 Agent를 다시 호출하지 않는다.
복구 주기는 `ANALYSIS_FAILURE_RECONCILE_INTERVAL_MS`(기본 5000)로 설정한다. 한 주기에 최대 20건을 처리한다.

최신 Agent(#66)의 완료 작업 중복 방지는 현재 프로세스 메모리/checkpointer 범위이므로, Agent 재시작 후 404만 보고 자동 재POST하지 않는다.
DLQ 수동 재실행 전 GitHub 반영 여부를 확인한다.

## 협업 범위

기존 analysis 프로덕션 파일은 `AnalysisCoordinator.start`와 관련 import만 변경했다.
`pollPending`, `AnalysisStore`, `AnalysisTask`, 기존 컨트롤러와 프론트는 변경하지 않는다.
큐 상태는 별도 `queue` 패키지·테이블에서 관리한다. Swagger 접수 설명은 별도 OperationCustomizer로 갱신한다.
임나윤 담당 InternalTaskController와 AnalysisStore 단계 갱신 메서드는 이 변경과 독립적으로 추가할 수 있다.

## 검증 및 증빙

- `cd backend && ./gradlew build`
- PR #81 리뷰 보완: 오래된/최근/시각 없는 SUBMITTING 복구, 각 DB 예외의 제한 재시도, 접수 후 DB 실패 시 POST 1회 유지, 실패 기록 ACK/NACK·완료 결과 보존 테스트.
- 전용 vhost에서 실제 RabbitMQ의 DB 장애→DLQ, DB 복구→FAILED, 후속 정상 작업 처리 검증. 아래 재현 명령을 참고한다.
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

## PR #81 복구 테스트 재현

아래는 개발용 큐와 분리된 검증 vhost를 만들고 실제 RabbitMQ 테스트를 포함해 전체 빌드를 실행한다.
DB는 테스트 전용 H2이며, Agent 응답과 DB 장애를 주입하고 대기 함수는 모의 처리해 테스트를 빠르게 실행한다.

```bash
# 저장소 루트, RabbitMQ 실행 상태에서
docker exec meettoticket-rabbitmq rabbitmqctl add_vhost mtt81-review
docker exec meettoticket-rabbitmq rabbitmqctl set_permissions -p mtt81-review meettoticket '.*' '.*' '.*'
cd backend
RABBITMQ_REVIEW_TESTS=true RABBITMQ_REVIEW_VHOST=mtt81-review ./gradlew build
# 검증이 끝나면 전용 vhost만 제거한다. 개발용 / vhost는 변경하지 않는다.
docker exec meettoticket-rabbitmq rabbitmqctl delete_vhost mtt81-review
```

일반 `./gradlew build`는 실제 브로커 테스트 3건을 제외하고 실행한다. 기본 자동 테스트에서 RabbitMQ 접속은 필요하지 않다.
기존 PDF·캡처는 최초 큐 구현 검증 당시의 자료이며, 리뷰 수정 이후 관리 화면에는 실패 기록 복구 큐가 하나 추가된다.
