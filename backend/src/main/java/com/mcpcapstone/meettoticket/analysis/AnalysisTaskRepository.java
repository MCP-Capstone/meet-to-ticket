package com.mcpcapstone.meettoticket.analysis;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, UUID> {
    boolean existsByMeetingId(Long meetingId);

    List<AnalysisTask> findByStatusInOrderByCreatedAt(List<TaskStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AnalysisTask t where t.id=:id")
    Optional<AnalysisTask> findForUpdate(UUID id);
}
