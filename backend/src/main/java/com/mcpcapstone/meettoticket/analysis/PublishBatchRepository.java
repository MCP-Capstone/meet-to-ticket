package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;
import java.util.UUID;

public interface PublishBatchRepository extends JpaRepository<PublishBatch, UUID> {
    // 같은 묶음의 결과가 겹쳐 들어와도 한 번만 반영한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PublishBatch b where b.id = :id")
    Optional<PublishBatch> findForUpdate(UUID id);
}
