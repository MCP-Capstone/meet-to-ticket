package com.mcpcapstone.meettoticket.meeting;

import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.common.error.*;
import com.mcpcapstone.meettoticket.project.*;
import com.mcpcapstone.meettoticket.user.UserRepository;

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
            Long meetingId,
            Long projectId,
            String title,
            LocalDate meetingDate,
            List<Long> participantIds,
            String originalFilename,
            boolean readyForAnalysis) {}
}
