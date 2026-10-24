package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.*;

import java.time.OffsetDateTime;
import java.util.*;

// 모든 상태 변경은 현재 상태 조건을 건 UPDATE/DELETE 한 문장이다. 영향받은 행 수가 0이면 다른 요청이 먼저 바꾼 것이다.
// 일괄 UPDATE는 영속성 컨텍스트를 갱신하지 않으므로, 바꾼 뒤의 상태는 엔티티가 아니라 statusOf로 다시 읽는다.
public interface PublicationRepository extends JpaRepository<Publication, Long> {
    // 새 예약. 같은 (target, idempotency_key)가 이미 있으면 아무것도 하지 않고 0을 돌려준다.
    // 충돌 대상을 적지 않은 것은 H2(테스트)가 대상 지정을 받지 않아서다. 이 테이블의 유니크는 그 키뿐이다.
    // 동시에 같은 키를 넣으면 늦은 쪽은 먼저 넣은 쪽이 끝날 때까지 기다린 뒤 0을 받는다(예외로 트랜잭션이 깨지지 않는다).
    @Modifying
    @Query(nativeQuery = true, value = """
            insert into publications (task_item_id, target, operation, idempotency_key, status, attempt_count,
                                      publish_batch_id, created_at, reserved_at, updated_at)
            values (:taskItemId, :target, :operation, :key, 'RESERVED', 1, :batchId, :now, :now, :now)
            on conflict do nothing""")
    int insertReserved(UUID taskItemId, String target, String operation, String key, UUID batchId, OffsetDateTime now);

    // 끝난 행에서 새 시도를 시작한다. from에 든 상태일 때만 RESERVED로 바꾸고 시도 횟수를 늘린다.
    @Modifying
    @Query("""
            update Publication p
               set p.status = com.mcpcapstone.meettoticket.analysis.PublicationStatus.RESERVED,
                   p.attemptCount = p.attemptCount + 1, p.publishBatchId = :batchId,
                   p.reservedAt = :now, p.sentAt = null, p.updatedAt = :now,
                   p.errorCode = null, p.externalId = null, p.externalUrl = null
             where p.target = :target and p.idempotencyKey = :key and p.status in :from""")
    int reopen(PublishTarget target, String key, Collection<PublicationStatus> from, UUID batchId, OffsetDateTime now);

    // Agent가 접수한 묶음의 예약을 SENT로 바꾼다. 보고가 먼저 도착해 이미 결과 상태인 행은 건드리지 않는다.
    @Modifying
    @Query("""
            update Publication p
               set p.status = com.mcpcapstone.meettoticket.analysis.PublicationStatus.SENT, p.sentAt = :now, p.updatedAt = :now
             where p.publishBatchId = :batchId
               and p.status = com.mcpcapstone.meettoticket.analysis.PublicationStatus.RESERVED""")
    int markSent(UUID batchId, OffsetDateTime now);

    // 결과 상태로 바꾼다. from에 든 상태일 때만 바뀐다.
    @Modifying
    @Query("""
            update Publication p
               set p.status = :to, p.errorCode = :errorCode, p.externalId = :externalId,
                   p.externalUrl = :externalUrl, p.updatedAt = :now
             where p.id = :id and p.status in :from""")
    int finish(Long id, PublicationStatus to, Collection<PublicationStatus> from,
               String errorCode, String externalId, String externalUrl, OffsetDateTime now);

    // 외부 쓰기가 없었던 예약(발행 생략)을 지운다. from에 든 상태일 때만 지운다.
    @Modifying
    @Query("delete from Publication p where p.id = :id and p.status in :from")
    int discard(Long id, Collection<PublicationStatus> from);

    // 접수 확인에 실패한 묶음의 진행 중 예약을 모두 실패로 돌린다. 반영된 적이 없는 것이 확실할 때만 쓴다.
    @Modifying
    @Query("""
            update Publication p
               set p.status = com.mcpcapstone.meettoticket.analysis.PublicationStatus.FAILED, p.errorCode = :errorCode, p.updatedAt = :now
             where p.publishBatchId = :batchId and p.status in :from""")
    int failBatch(UUID batchId, Collection<PublicationStatus> from, String errorCode, OffsetDateTime now);

    @Query("select p.status from Publication p where p.target = :target and p.idempotencyKey = :key")
    Optional<PublicationStatus> statusOf(PublishTarget target, String key);

    // 이 묶음으로 보낸 티켓의 진행 중 행 ID.
    @Query("select p.id from Publication p where p.taskItem.id = :taskItemId and p.publishBatchId = :batchId and p.status in :statuses")
    Optional<Long> findIdByBatch(UUID taskItemId, UUID batchId, Collection<PublicationStatus> statuses);

    // 담당자가 확인할 결과 모름 행의 ID.
    @Query("select p.id from Publication p where p.taskItem.id = :taskItemId and p.target = :target and p.status = :status")
    List<Long> findIds(UUID taskItemId, PublishTarget target, PublicationStatus status);

    Optional<Publication> findByTargetAndIdempotencyKey(PublishTarget target, String idempotencyKey);

    List<Publication> findByTaskItem_IdOrderById(UUID taskItemId);
}
