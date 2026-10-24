package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface TaskItemRevisionRepository extends JpaRepository<TaskItemRevision, Long> {
    List<TaskItemRevision> findByTaskItem_IdOrderByRev(UUID taskItemId);

    Optional<TaskItemRevision> findFirstByTaskItem_IdOrderByRevDesc(UUID taskItemId);

    boolean existsByTaskItem_IdAndKind(UUID taskItemId, RevisionKind kind);
}
