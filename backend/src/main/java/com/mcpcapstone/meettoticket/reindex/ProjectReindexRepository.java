package com.mcpcapstone.meettoticket.reindex;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectReindexRepository extends JpaRepository<ProjectReindex, Long> {
    Optional<ProjectReindex> findByProjectId(Long projectId);
}
