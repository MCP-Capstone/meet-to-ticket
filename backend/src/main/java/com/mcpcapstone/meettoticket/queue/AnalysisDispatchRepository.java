package com.mcpcapstone.meettoticket.queue;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnalysisDispatchRepository extends JpaRepository<AnalysisDispatch, UUID> {
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from AnalysisDispatch d where d.taskId = :id")
	Optional<AnalysisDispatch> findForUpdate(UUID id);

	List<AnalysisDispatch> findTop100ByPublishedFalseAndState(AnalysisDispatch.State state);
}
