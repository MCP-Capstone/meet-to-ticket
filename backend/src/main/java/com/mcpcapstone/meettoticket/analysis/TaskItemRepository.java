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

    // 일괄 발행 시작 시 행 잠금을 잡아 두 요청이 같은 CONFIRMED 티켓을 동시에 가져가지 못하게 한다.
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from TaskItem i join fetch i.task t join fetch t.meeting m join fetch m.project p left join fetch i.assignee a left join fetch a.user where i.assignee.user.id = :userId and i.ticketStatus = :status order by t.createdAt, i.id")
    List<TaskItem> lockByAssigneeUserIdAndTicketStatus(Long userId, TicketStatus status);
}
