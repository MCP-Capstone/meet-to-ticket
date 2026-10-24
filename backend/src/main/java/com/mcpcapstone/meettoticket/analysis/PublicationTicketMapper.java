package com.mcpcapstone.meettoticket.analysis;

import com.mcpcapstone.meettoticket.agent.PublishGateway;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;

// 저장된 내용을 발행/미리보기 양쪽에 똑같이 전달한다. 이 변환은 상태를 변경하지 않는다.
@Component
@RequiredArgsConstructor
public class PublicationTicketMapper {
	private final JsonCodec json;
	private final ProjectMemberRepository members;
	PublishGateway.Ticket toTicket(TaskItem i) {
		var meeting = i.getTask().getMeeting();
		var project = meeting.getProject();
		JsonNode content = json.read(i.getContentJson());
		ProcessingType type = i.getProcessingType();
		// 처음 정해진 값(Agent 제안)과 다르면 담당자가 고른 값이다. Agent는 발행 직전 재대조에서 이 선택을 덮어쓰지 않는다.
		boolean edited = type != ProcessingType.from(i.getAction(), content);
		Integer target = null;
		if (type != ProcessingType.CREATE) {
			JsonNode number = content.path("dedupe").path("issue_number");
			target = number.isInt() ? number.intValue() : null;
			// 연결, 갱신할 기존 이슈를 모르면 신규 발행으로 보낸다. Agent가 발행 직전에 다시 대조한다.
			if (target == null) type = ProcessingType.CREATE;
		}
		PublishTarget publishTarget = i.getPublishTarget();
		boolean commentOnly = i.isCommentRetry();
		if (commentOnly) {
			type = ProcessingType.UPDATE;
			target = i.getGithubIssueNumber(); // 재대조 후 실제 갱신한 이슈. 초기 dedupe 대상과 다를 수 있다.
		}
		return new PublishGateway.Ticket(
				i.getId().toString(),
				new PublishGateway.Project(project.getId(), project.getName(), project.getGithubOwner(), project.getGithubRepo(),
						project.getNotionPageUrl(), project.getSlackChannelId()),
				meeting.getId(), meeting.getTitle(), meeting.getMeetingDate(),
				i.getTitle(), content, type.name(), target, edited, i.mustRecheckOnPublish(),
				publishTarget.name(), publishTarget == PublishTarget.SLACK ? slackMembers(project.getId()) : List.of(),
				"CONFIRMED", commentOnly);
	}

	// Slack 연결이 확인된 멤버만 멘션할 수 있다. 이름은 공지 문구의 검토자 이름과 맞춘다.
	private List<PublishGateway.SlackMember> slackMembers(Long projectId) {
		return members.findByProjectIdOrderById(projectId).stream()
				.filter(m -> m.getUser() != null && m.getUser().getSlackUserId() != null)
				.map(m -> new PublishGateway.SlackMember(m.getName(), m.getUser().getSlackUserId()))
				.toList();
	}

}
