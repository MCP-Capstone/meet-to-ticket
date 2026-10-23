package com.mcpcapstone.meettoticket.meeting;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;
import java.util.List;
import java.time.LocalDate;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {
	@Query("select m.project.id as projectId, max(m.meetingDate) as lastMeetingDate from Meeting m where m.project.id in :projectIds group by m.project.id")
	List<LatestMeeting> findLatestMeetingsByProjectIds(List<Long> projectIds);

	interface LatestMeeting {
		Long getProjectId();
		LocalDate getLastMeetingDate();
	}

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meeting m where m.id=:id")
    Optional<Meeting> findForAnalysis(Long id);

    // 프로젝트 삭제(TASK-009)용
    @Modifying
    @Query("delete from Meeting m where m.project.id = :projectId")
    int deleteByProjectId(Long projectId);
}
