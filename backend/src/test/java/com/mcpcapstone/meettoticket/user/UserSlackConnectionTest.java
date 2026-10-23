package com.mcpcapstone.meettoticket.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.mcpcapstone.meettoticket.project.dto.ProjectDetail.ConnectionStatus;

import org.junit.jupiter.api.Test;

class UserSlackConnectionTest {
	private User synced() {
		User user = new User("login", "hash", "이름", null, "a@example.com");
		user.recordSlackConnection(ConnectionStatus.CONNECTED, "U1");
		return user;
	}

	@Test
	void 일시_오류는_이미_동기화된_slack_ID를_지우지_않는다() {
		User user = synced();

		user.recordSlackConnection(ConnectionStatus.ERROR, null);

		assertThat(user.getSlackUserId()).isEqualTo("U1");
		assertThat(user.slackConnectionStatus()).isEqualTo(ConnectionStatus.ERROR);
	}

	@Test
	void 워크스페이스에_없으면_slack_ID를_지운다() {
		User user = synced();

		user.recordSlackConnection(ConnectionStatus.NOT_CONNECTED, null);

		assertThat(user.getSlackUserId()).isNull();
	}

	@Test
	void 이메일이_바뀌면_이전_slack_ID를_버린다() {
		User user = synced();

		user.updateSlackEmail("b@example.com");
		user.recordSlackConnection(ConnectionStatus.ERROR, null);

		assertThat(user.getSlackUserId()).isNull();
	}
}
