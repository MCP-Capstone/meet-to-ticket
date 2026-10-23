package com.mcpcapstone.meettoticket.reindex;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectReindexRepository extends JpaRepository<ProjectReindex, Long> {
    Optional<ProjectReindex> findByProjectId(Long projectId);

    /** 시작 요청의 확인과 초기화를 직렬화하기 위해 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProjectReindex r where r.project.id = :projectId")
    Optional<ProjectReindex> findByProjectIdForUpdate(@Param("projectId") Long projectId);
}
