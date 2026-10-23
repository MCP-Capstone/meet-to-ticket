package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;
import java.util.UUID;

public interface PublicationBatchRepository extends JpaRepository<PublicationBatch, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PublicationBatch b where b.id = :id")
    Optional<PublicationBatch> findForUpdate(UUID id);
}
