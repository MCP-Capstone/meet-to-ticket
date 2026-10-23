package com.mcpcapstone.meettoticket.project;

import com.mcpcapstone.meettoticket.analysis.AnalysisTaskRepository;
import com.mcpcapstone.meettoticket.analysis.TaskItemRepository;
import com.mcpcapstone.meettoticket.analysis.TaskStatus;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.queue.AnalysisDispatchRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 프로젝트 삭제 (REQ-005). 서비스 안의 기록만 지우고, 이미 발행한 GitHub 이슈, Slack 메시지, Notion 페이지는 그대로 둔다.
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectDeletionService {
    private final ProjectService access;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final MeetingRepository meetings;
    private final AnalysisTaskRepository tasks;
    private final TaskItemRepository items;
    private final AnalysisDispatchRepository dispatches;

    @Transactional
    public void delete(Long projectId, String confirmName, Long userId) {
        access.requireAdmin(projectId, userId);
        Project project =
                projects.findById(projectId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.PROJECT_ACCESS_DENIED));
        if (!project.getName().equals(confirmName.trim()))
            throw new BusinessException(ErrorCode.PROJECT_NAME_MISMATCH);
        // 처리 중인 작업을 지우면 Agent가 보내는 진행 보고와 결과를 받을 곳이 사라진다.
        if (tasks.existsByMeetingProjectIdAndStatusIn(projectId, List.of(TaskStatus.QUEUED, TaskStatus.RUNNING)))
            throw new BusinessException(ErrorCode.PROJECT_ANALYSIS_IN_PROGRESS);

        // 외래 키를 따라 아래에서 위로 지운다.
        int itemCount = items.deleteByProjectId(projectId);
        dispatches.deleteByProjectId(projectId);
        int taskCount = tasks.deleteByProjectId(projectId);
        int meetingCount = meetings.deleteByProjectId(projectId);
        members.deleteByProjectId(projectId);
        projects.deleteById(projectId);
        // TODO(TASK-037): rag-mcp 색인 삭제 도구가 생기면 여기서 이 저장소 색인을 지운다.
        log.info("프로젝트 {} 삭제: 회의 {}건, 분석 작업 {}건, 티켓 {}건", projectId, meetingCount, taskCount, itemCount);
    }
}
