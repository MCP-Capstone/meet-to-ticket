package com.mcpcapstone.meettoticket.queue;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisDispatchRepository extends JpaRepository<AnalysisDispatch, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from AnalysisDispatch d where d.taskId = :id")
	Optional<AnalysisDispatch> findForUpdate(UUID id);

	List<AnalysisDispatch> findTop100ByPublishedFalseAndState(AnalysisDispatch.State state);

	// 프로젝트 삭제(TASK-009)용
	@Modifying
	@Query("delete from AnalysisDispatch d where d.taskId in (select t.id from AnalysisTask t where t.meeting.project.id = :projectId)")
	int deleteByProjectId(Long projectId);
}
