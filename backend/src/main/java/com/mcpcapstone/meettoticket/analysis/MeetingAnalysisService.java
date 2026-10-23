package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.meeting.MeetingService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class MeetingAnalysisService {
    private final MeetingService meetings;
    private final AnalysisCoordinator coordinator;

    /** 회의 저장과 분석 작업 접수를 한 트랜잭션으로 묶는다. 접수가 실패하면 회의도 저장하지 않는다. */
    @Transactional
    public AnalysisCoordinator.Started request(
            Long projectId,
            Long userId,
            String meetingDate,
            String title,
            String text,
            MultipartFile file) {
        var meeting = meetings.register(projectId, userId, meetingDate, title, text, file);
        return coordinator.start(meeting.getId(), userId);
    }
}
