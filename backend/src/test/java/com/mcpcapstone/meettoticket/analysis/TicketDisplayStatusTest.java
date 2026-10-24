package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static com.mcpcapstone.meettoticket.analysis.TicketDisplayStatus.*;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

// 사용자 노출 4상태 파생 (#264). TicketStatus 10개 값을 모두 확인한다.
class TicketDisplayStatusTest {
	@Test
	void 열_개_상태가_모두_정해진_표시_상태로_바뀐다() {
		Map<TicketStatus, TicketDisplayStatus> expected = new java.util.EnumMap<>(TicketStatus.class);
		expected.put(TicketStatus.ASSIGNEE_REQUIRED, REVIEW_PENDING);
		expected.put(TicketStatus.PENDING_REVIEW, REVIEW_PENDING);
		expected.put(TicketStatus.CONFIRMED, REVIEW_PENDING);
		expected.put(TicketStatus.PUBLISHING, PUBLISHING);
		expected.put(TicketStatus.PUBLISHED, PUBLISHED);
		expected.put(TicketStatus.LINKED, PUBLISHED);
		expected.put(TicketStatus.UPDATED, PUBLISHED);
		expected.put(TicketStatus.SKIPPED, NEEDS_ATTENTION);
		expected.put(TicketStatus.FAILED, NEEDS_ATTENTION);
		expected.put(TicketStatus.REJECTED, null);

		// 새 TicketStatus 값이 생기면 이 표에도 넣어야 한다.
		assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(TicketStatus.class));
		expected.forEach((status, display) -> assertThat(TicketDisplayStatus.of(status, null))
			.as(status.name()).isEqualTo(display));
	}

	@Test
	void 발행_생략은_발행됨에_합치지_않는다() {
		assertThat(TicketDisplayStatus.of(TicketStatus.SKIPPED, null)).isNotEqualTo(PUBLISHED).isEqualTo(NEEDS_ATTENTION);
	}

	@Test
	void 알림_댓글이_실패한_갱신은_발행됨이_아니라_확인_필요다() {
		assertThat(TicketDisplayStatus.of(TicketStatus.UPDATED, "COMMENT_FAILED")).isEqualTo(NEEDS_ATTENTION);
		assertThat(TicketDisplayStatus.of(TicketStatus.UPDATED, null)).isEqualTo(PUBLISHED);
		// 오류 코드는 UPDATED일 때만 본다. 이전 실패 사유가 남은 다른 상태는 상태를 따른다.
		assertThat(TicketDisplayStatus.of(TicketStatus.PUBLISHING, "COMMENT_FAILED")).isEqualTo(PUBLISHING);
		assertThat(TicketDisplayStatus.of(TicketStatus.CONFIRMED, "GITHUB_FORBIDDEN")).isEqualTo(REVIEW_PENDING);
	}
}
