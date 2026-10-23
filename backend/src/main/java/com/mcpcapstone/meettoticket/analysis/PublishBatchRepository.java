package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublishBatchRepository extends JpaRepository<PublishBatch, UUID> {
    // 같은 묶음의 결과가 겹쳐 들어와도 한 번만 반영한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PublishBatch b where b.id = :id")
    Optional<PublishBatch> findForUpdate(UUID id);

    @Query("select b.id from PublishBatch b where b.status = :status and b.createdAt < :before")
    List<UUID> findIdsByStatusCreatedBefore(PublishBatchStatus status, OffsetDateTime before);
}
