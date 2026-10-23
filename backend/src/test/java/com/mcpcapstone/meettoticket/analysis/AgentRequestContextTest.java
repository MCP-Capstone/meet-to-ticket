package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.mcpcapstone.meettoticket.agent.AgentContracts;
import com.mcpcapstone.meettoticket.common.JsonCodec;
import com.mcpcapstone.meettoticket.meeting.Meeting;
import com.mcpcapstone.meettoticket.meeting.MeetingRepository;
import com.mcpcapstone.meettoticket.project.Project;
import com.mcpcapstone.meettoticket.project.ProjectMember;
import com.mcpcapstone.meettoticket.project.ProjectMemberRepository;
import com.mcpcapstone.meettoticket.project.ProjectRepository;
import com.mcpcapstone.meettoticket.project.dto.ProjectDetail;
import com.mcpcapstone.meettoticket.user.User;
import com.mcpcapstone.meettoticket.user.UserRepository;

// Agent가 맥락으로 읽을 Notion 기획 페이지를 요청에 담는다 (TASK-019).
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentRequestContextTest {
	@Autowired AnalysisStore store;
	@Autowired UserRepository users;
	@Autowired ProjectRepository projects;
	@Autowired ProjectMemberRepository members;
	@Autowired MeetingRepository meetings;
	@Autowired JsonCodec json;

	private AgentContracts.ProjectData request(String notionUrl, ProjectDetail.ConnectionStatus status) {
		String suffix = UUID.randomUUID().toString();
		User user = users.save(new User("ctx-" + suffix, "unused-test-hash", "맥락 테스트"));
		Project project = projects.save(new Project("맥락 테스트", "test-owner", "ctx-" + suffix));
		project.update("맥락 테스트", null, "test-owner", "ctx-" + suffix, notionUrl, null);
		if (status != null) project.updateNotionConnectionStatus(status);
		ProjectMember member = members.save(new ProjectMember(project, user, user.getName(), null));
		Meeting meeting = meetings.save(new Meeting(project, user, "맥락 회의", LocalDate.of(2026, 10, 23),
				json.write(List.of(member.getId())), "ctx.txt", "맥락 테스트 원문"));
		return store.create(meeting.getId(), user.getId(), "LIVE").project();
	}

	@Test
	void Notion_기획_페이지를_Agent_요청에_담는다() {
		String url = "https://www.notion.so/plan-123";
		assertThat(request(url, null).notionPageUrl()).isEqualTo(url); // 연결 확인 전이어도 보낸다
		assertThat(request(url, ProjectDetail.ConnectionStatus.CONNECTED).notionPageUrl()).isEqualTo(url);
	}

	@Test
	void Notion이_없거나_연결_오류면_보내지_않는다() {
		assertThat(request(null, null).notionPageUrl()).isNull();
		assertThat(request("https://www.notion.so/plan-123", ProjectDetail.ConnectionStatus.ERROR).notionPageUrl()).isNull();
	}
}
