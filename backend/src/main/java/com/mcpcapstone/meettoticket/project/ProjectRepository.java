package com.mcpcapstone.meettoticket.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByGithubOwnerAndGithubRepo(String owner, String repo);

    // GitHub 저장소 이름은 대소문자를 구분하지 않는다.
    Optional<Project> findByGithubOwnerIgnoreCaseAndGithubRepoIgnoreCase(String owner, String repo);
}
