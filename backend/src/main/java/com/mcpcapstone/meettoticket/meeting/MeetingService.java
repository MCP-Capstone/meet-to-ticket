package com.mcpcapstone.meettoticket.meeting;

import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.UserRepository;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MeetingService {
    private final MeetingRepository meetings;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final ProjectService access;
    private final UserRepository users;
    private final MeetingParser parser;
    private final JsonCodec json;

    @Transactional
    public MeetingResponse upload(Long projectId, Long userId, MultipartFile file) {
        access.requireAccess(projectId, userId);
        String filename = file.getOriginalFilename();
        if (file.isEmpty()
                || filename == null
                || filename.length() > 255
                || filename.contains("/")
                || filename.contains("\\")
                || !filename.toLowerCase(Locale.ROOT).endsWith(".txt"))
            throw new BusinessException(ErrorCode.INVALID_MEETING_FILE);
        MeetingParser.Parsed parsed;
        try {
            parsed = parser.parse(file.getBytes());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INVALID_MEETING_FILE);
        }
        List<ProjectMember> all = members.findByProjectIdOrderById(projectId);
        List<Long> ids = new ArrayList<>();
        for (String name : parsed.participantNames()) {
            List<ProjectMember> found = all.stream().filter(m -> m.getName().equals(name)).toList();
            if (found.size() != 1)
                throw new BusinessException(ErrorCode.PARTICIPANT_MAPPING_FAILED);
            ids.add(found.getFirst().getId());
        }
        Meeting m =
                meetings.save(
                        new Meeting(
                                projects.findById(projectId)
                                        .orElseThrow(
                                                () ->
                                                        new BusinessException(
                                                                ErrorCode.PROJECT_ACCESS_DENIED)),
                                users.findById(userId)
                                        .orElseThrow(
                                                () ->
                                                        new BusinessException(
                                                                ErrorCode.UNAUTHORIZED)),
                                parsed.title(),
                                parsed.date(),
                                json.write(ids),
                                filename,
                                parsed.content()));
        return response(m);
    }

    public MeetingResponse get(Long id, Long userId) {
        Meeting m =
                meetings.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.MEETING_NOT_FOUND));
        access.requireAccess(m.getProject().getId(), userId);
        return response(m);
    }

    private MeetingResponse response(Meeting m) {
        List<Long> ids = new ArrayList<>();
        json.read(m.getParticipantsJson()).forEach(n -> ids.add(n.longValue()));
        return new MeetingResponse(
                m.getId(),
                m.getProject().getId(),
                m.getTitle(),
                m.getMeetingDate(),
                ids,
                m.getOriginalFilename(),
                true);
    }

    public record MeetingResponse(
            @Schema(description = "회의 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
            Long meetingId,
            @Schema(description = "프로젝트 ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
            Long projectId,
            @Schema(description = "회의 제목", requiredMode = Schema.RequiredMode.REQUIRED, example = "CSV 내보내기 중간 점검")
            String title,
            @Schema(description = "회의 날짜. 상대 기한 계산 기준", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-22")
            LocalDate meetingDate,
            @Schema(description = "ProjectMember ID 배열. 이름은 팀원 조회로 연결", requiredMode = Schema.RequiredMode.REQUIRED)
            List<Long> participantIds,
            @Schema(description = "원본 파일명", requiredMode = Schema.RequiredMode.REQUIRED, example = "meeting.txt")
            String originalFilename,
            @Schema(description = "회의 등록이 완료되어 분석 요청 가능한지 여부", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
            boolean readyForAnalysis) {}
}
