package com.mcpcapstone.meettoticket.meeting;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;

import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Meeting m where m.id=:id")
    Optional<Meeting> findForAnalysis(Long id);
}
