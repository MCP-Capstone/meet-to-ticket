package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mcpcapstone.meettoticket.agent.AgentContracts;

import tools.jackson.databind.json.JsonMapper;

// Agent가 분류한 발행 대상(contentJson.publish_target)으로 티켓을 저장한다 (TASK-020).
class PublishTargetTest {
	private final JsonMapper mapper = JsonMapper.builder().build();

	private TaskItem saved(String content, String action) {
		var item = new AgentContracts.Item(UUID.randomUUID(), null, "티켓", mapper.readTree(content), action, null,
				"PENDING", "DRAFT", null, null, null);
		return new TaskItem(null, item, content);
	}

	@Test
	void Agent가_정한_발행_대상으로_저장한다() {
		assertThat(saved("{\"publish_target\":\"NOTION\"}", "CREATE").getPublishTarget()).isEqualTo(PublishTarget.NOTION);
		assertThat(saved("{\"publish_target\":\"SLACK\"}", "CREATE").getPublishTarget()).isEqualTo(PublishTarget.SLACK);
		assertThat(saved("{\"publish_target\":\"GITHUB\"}", "UPDATE").getProcessingType()).isEqualTo(ProcessingType.UPDATE);
	}

	@Test
	void 값이_없거나_모르는_값이면_GitHub이다() {
		assertThat(saved("{}", "CREATE").getPublishTarget()).isEqualTo(PublishTarget.GITHUB);
		assertThat(saved("{\"publish_target\":\"JIRA\"}", "CREATE").getPublishTarget()).isEqualTo(PublishTarget.GITHUB);
	}

	@Test
	void GitHub_이외의_대상은_항상_신규_발행이다() {
		// 중복 판단 제안(LINK)이나 Agent action(UPDATE)이 있어도 Notion·Slack은 CREATE다 (REQ-015)
		String notion = "{\"publish_target\":\"NOTION\",\"dedupe\":{\"handling\":{\"type\":\"LINK\"}}}";
		assertThat(saved(notion, "UPDATE").getProcessingType()).isEqualTo(ProcessingType.CREATE);
	}
}
