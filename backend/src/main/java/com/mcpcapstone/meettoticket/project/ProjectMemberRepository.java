package com.mcpcapstone.meettoticket.project;

import org.springframework.data.jpa.repository.*;

import java.util.List;
import java.util.Optional;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);

    @Query("select m.project from ProjectMember m where m.user.id = :userId order by m.project.id")
    List<Project> findProjectsForUser(Long userId);

    @EntityGraph(attributePaths = {"user"})
    List<ProjectMember> findByProjectIdOrderById(Long projectId);

    boolean existsByProjectIdAndName(Long projectId, String name);

    // 관리자 지정/해제를 한 번에 하나씩 처리해, 동시에 해제해도 관리자가 0명이 되지 않게 한다.
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from ProjectMember m where m.project.id = :projectId and m.role = :role")
    List<ProjectMember> lockByProjectIdAndRole(Long projectId, ProjectRole role);
}
