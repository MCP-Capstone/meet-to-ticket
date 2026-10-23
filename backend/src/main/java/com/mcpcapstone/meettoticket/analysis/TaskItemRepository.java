package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.*;

import java.util.*;

public interface TaskItemRepository extends JpaRepository<TaskItem, UUID> {
    List<TaskItem> findByTaskIdOrderById(UUID taskId);

    // 프로젝트 삭제(TASK-009)용
    @Modifying
    @Query("delete from TaskItem i where i.task.id in (select t.id from AnalysisTask t where t.meeting.project.id = :projectId)")
    int deleteByProjectId(Long projectId);

    // 로그인 사용자가 담당인 티켓. projectId가 null이면 모든 프로젝트.
    @Query("""
            select i from TaskItem i
              join fetch i.task t join fetch t.meeting m join fetch m.project p
             where i.assignee.user.id = :userId and (:projectId is null or p.id = :projectId)
             order by t.createdAt desc, i.id""")
    List<TaskItem> findAssignedTo(Long userId, Long projectId);
}
