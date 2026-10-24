package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface TicketStatusHistoryRepository extends JpaRepository<TicketStatusHistory, Long> {
    // 이 시각 이후에 이 사유로 상태가 바뀐 적이 있는지. 복구 워커가 접수 취소 여부를 확인한다.
    boolean existsByTaskItem_IdAndReasonAndCreatedAtGreaterThanEqual(java.util.UUID taskItemId, String reason, java.time.OffsetDateTime since);

    List<TicketStatusHistory> findByTaskItem_IdOrderByCreatedAtAscIdAsc(UUID taskItemId);
}
