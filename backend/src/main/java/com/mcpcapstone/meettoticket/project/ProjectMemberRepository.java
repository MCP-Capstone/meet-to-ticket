package com.mcpcapstone.meettoticket.project;

import org.springframework.data.jpa.repository.*;

import java.util.List;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    @Query("select m.project from ProjectMember m where m.user.id = :userId order by m.project.id")
    List<Project> findProjectsForUser(Long userId);

    @EntityGraph(attributePaths = {"user"})
    List<ProjectMember> findByProjectIdOrderById(Long projectId);

    boolean existsByProjectIdAndName(Long projectId, String name);
}
