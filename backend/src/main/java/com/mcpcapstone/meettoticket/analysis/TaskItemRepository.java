package com.mcpcapstone.meettoticket.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.*;

public interface TaskItemRepository extends JpaRepository<TaskItem, UUID> {
    List<TaskItem> findByTaskIdOrderById(UUID taskId);
}
