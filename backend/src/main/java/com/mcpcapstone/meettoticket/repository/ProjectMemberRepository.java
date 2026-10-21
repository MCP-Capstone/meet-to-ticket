package com.mcpcapstone.meettoticket.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mcpcapstone.meettoticket.entity.ProjectMember;

import jakarta.persistence.LockModeType;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

	// 회의 저장 트랜잭션 동안 조회한 멤버의 변경/삭제를 방지한다.
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select member from ProjectMember member where member.id in :ids")
	List<ProjectMember> findAllForMeeting(@Param("ids") Collection<Long> ids);
}
