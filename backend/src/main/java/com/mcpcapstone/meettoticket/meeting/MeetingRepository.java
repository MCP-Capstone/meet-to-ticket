package com.mcpcapstone.meettoticket.meeting;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meeting m where m.id=:id")
    Optional<Meeting> findForAnalysis(Long id);

    // 프로젝트 삭제(TASK-009)용
    @Modifying
    @Query("delete from Meeting m where m.project.id = :projectId")
    int deleteByProjectId(Long projectId);
}
