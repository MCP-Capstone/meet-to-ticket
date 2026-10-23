package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.*;

public interface TaskItemRepository extends JpaRepository<TaskItem, UUID> {
    List<TaskItem> findByTaskIdOrderById(UUID taskId);

    // 프로젝트 삭제(TASK-009)용
    @Modifying
    @Query("delete from TaskItem i where i.task.id in (select t.id from AnalysisTask t where t.meeting.project.id = :projectId)")
    int deleteByProjectId(Long projectId);
}
