package com.mcpcapstone.meettoticket.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mcpcapstone.meettoticket.common.error.BusinessException;
import com.mcpcapstone.meettoticket.common.error.ErrorCode;
import com.mcpcapstone.meettoticket.entity.Meeting;
import com.mcpcapstone.meettoticket.entity.ProjectMember;
import com.mcpcapstone.meettoticket.repository.MeetingRepository;
import com.mcpcapstone.meettoticket.repository.ProjectMemberRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingService {

	private final MeetingRepository meetingRepository;
	private final ProjectMemberRepository projectMemberRepository;

	@Transactional
	public Meeting save(Meeting meeting) {
		if (meeting == null || meeting.getProject() == null || meeting.getProject().getId() == null) {
			throw new BusinessException(ErrorCode.INVALID_MEETING_PARTICIPANTS);
		}
		List<Long> participantIds = meeting.getParticipants();
		if (participantIds == null || participantIds.stream().anyMatch(Objects::isNull)
				|| new HashSet<>(participantIds).size() != participantIds.size()) {
			throw new BusinessException(ErrorCode.INVALID_MEETING_PARTICIPANTS);
		}
		// 빈 참여자 배열은 DBML이 허용한다. 원소가 있으면 모두 같은 프로젝트의 실제 멤버여야 한다.
		List<ProjectMember> members = participantIds.isEmpty() ? List.of()
				: projectMemberRepository.findAllForMeeting(participantIds);
		if (members.size() != participantIds.size() || members.stream().anyMatch(member ->
				!member.getProject().getId().equals(meeting.getProject().getId()))) {
			throw new BusinessException(ErrorCode.INVALID_MEETING_PARTICIPANTS);
		}
		return meetingRepository.save(meeting);
	}
}
