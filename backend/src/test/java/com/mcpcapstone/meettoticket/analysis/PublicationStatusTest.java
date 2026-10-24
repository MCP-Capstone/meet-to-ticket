package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;


// 발행 기록 상태 전이 표 (docs/publications.md). 표에 없는 전이는 모두 불허다.
class PublicationStatusTest {
	private static final Map<PublicationStatus, Set<PublicationStatus>> ALLOWED = Map.of(
			PublicationStatus.RESERVED, Set.of(PublicationStatus.SENT, PublicationStatus.CONFIRMED, PublicationStatus.UNCERTAIN, PublicationStatus.FAILED),
			PublicationStatus.SENT, Set.of(PublicationStatus.CONFIRMED, PublicationStatus.UNCERTAIN, PublicationStatus.FAILED),
			PublicationStatus.UNCERTAIN, Set.of(PublicationStatus.RESERVED, PublicationStatus.CONFIRMED, PublicationStatus.FAILED),
			PublicationStatus.FAILED, Set.of(PublicationStatus.RESERVED),
			PublicationStatus.CONFIRMED, Set.of());

	@Test
	void 전이_표의_모든_조합이_허용과_불허로_갈린다() {
		for (PublicationStatus from : PublicationStatus.values())
			for (PublicationStatus to : PublicationStatus.values())
				assertThat(from.canMoveTo(to))
						.as("%s -> %s", from, to)
						.isEqualTo(ALLOWED.get(from).contains(to));
	}

	@Test
	void 확정은_종결_상태이고_자기_자신으로_가는_전이는_없다() {
		assertThat(PublicationStatus.CONFIRMED.next()).isEmpty();
		for (PublicationStatus s : PublicationStatus.values()) assertThat(s.canMoveTo(s)).isFalse();
	}

	@Test
	void 진행_중_상태는_예약과_접수뿐이다() {
		for (PublicationStatus s : PublicationStatus.values())
			assertThat(s.inFlight()).isEqualTo(s == PublicationStatus.RESERVED || s == PublicationStatus.SENT);
	}

	@Test
	void 멱등키는_티켓_대상_작업이_같을_때만_같다() {
		var id = java.util.UUID.randomUUID();
		String key = Publication.keyOf(id, PublishTarget.GITHUB, PublishOperation.CREATE);
		assertThat(key).isEqualTo("v1:" + id + ":GITHUB:CREATE");
		assertThat(Publication.keyOf(id, PublishTarget.GITHUB, PublishOperation.CREATE)).isEqualTo(key);
		assertThat(Publication.keyOf(id, PublishTarget.NOTION, PublishOperation.CREATE)).isNotEqualTo(key);
		assertThat(Publication.keyOf(id, PublishTarget.GITHUB, PublishOperation.UPDATE)).isNotEqualTo(key);
		assertThat(Publication.keyOf(java.util.UUID.randomUUID(), PublishTarget.GITHUB, PublishOperation.CREATE)).isNotEqualTo(key);
		assertThat(key.length()).isLessThanOrEqualTo(200);
	}

	@Test
	void 작업은_처리_유형과_댓글_재전송으로_정한다() {
		assertThat(PublishOperation.of("CREATE", false)).isEqualTo(PublishOperation.CREATE);
		assertThat(PublishOperation.of("LINK", false)).isEqualTo(PublishOperation.LINK);
		assertThat(PublishOperation.of("UPDATE", false)).isEqualTo(PublishOperation.UPDATE);
		assertThat(PublishOperation.of("UPDATE", true)).isEqualTo(PublishOperation.COMMENT);
		assertThat(PublishOperation.of(null, false)).isEqualTo(PublishOperation.CREATE);
	}
}
