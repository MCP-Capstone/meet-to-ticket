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

    // 같은 티켓을 동시에 고치거나 상태를 바꾸지 못하게 잠근다. 연관 객체는 필요할 때 읽는다.
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TaskItem i where i.id = :id")
    Optional<TaskItem> findForUpdate(UUID id);

    // 로그인 사용자가 담당인 특정 상태의 티켓 ID. projectId가 null이면 모든 프로젝트.
    @Query("""
            select i.id from TaskItem i
             where i.assignee.user.id = :userId and i.ticketStatus = :status
               and (:projectId is null or i.task.meeting.project.id = :projectId)
             order by i.id""")
    List<UUID> findIdsAssignedTo(Long userId, TicketStatus status, Long projectId);

    // 한 발행 묶음의 티켓과 화면에 필요한 프로젝트 정보.
    @Query("""
            select i from TaskItem i
              join fetch i.task t join fetch t.meeting m join fetch m.project p
             where i.publishBatch.id = :batchId
             order by i.id""")
    List<TaskItem> findByBatch(UUID batchId);
}
