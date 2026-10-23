package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface PublicationBatchItemRepository extends JpaRepository<PublicationBatchItem, Long> {
    @Query("select i from PublicationBatchItem i join fetch i.ticket where i.batch.id = :batchId")
    List<PublicationBatchItem> findByBatchId(UUID batchId);
}
