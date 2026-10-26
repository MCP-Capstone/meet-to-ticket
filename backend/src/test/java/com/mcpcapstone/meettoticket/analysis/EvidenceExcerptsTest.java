package com.mcpcapstone.meettoticket.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceExcerptsTest {
	private static final String MEETING = """
			# 회의 요약 - 빈 결과 CSV 생성 오류 수정

			- 회의 참여자: 박종민, 허민규

			## [구간 1] 새로 발견한 오류
			사용자 필터 결과가 0건일 때 CSV 생성 API가 500을 반환한다. 허민규가 재현했다.

			## [구간 2] 수정 합의와 완료 조건
			CSV-08로 박종민이 23일 17시까지 수정한다. 0건일 때 헤더만 있는 파일을 생성한다.
			재현 로그와 0건 / 1건 회귀 결과가 완료 증빙이다.
			""";

	@Test
	void 근거가_들어_있는_단락을_제목과_함께_회의록_순서로_돌려준다() {
		var excerpts = EvidenceExcerpts.find(MEETING, List.of("재현 로그와 0건 / 1건 회귀 결과가 완료 증빙이다.", "허민규가 재현했다."));

		assertThat(excerpts).extracting(EvidenceExcerpts.Excerpt::heading)
				.containsExactly("[구간 1] 새로 발견한 오류", "[구간 2] 수정 합의와 완료 조건");
		assertThat(excerpts.get(1).text()).startsWith("CSV-08로 박종민이").endsWith("완료 증빙이다.");
		assertThat(excerpts.get(1).quotes()).containsExactly("재현 로그와 0건 / 1건 회귀 결과가 완료 증빙이다.");
	}

	@Test
	void 같은_단락의_근거는_하나로_묶고_줄바꿈이_달라도_찾는다() {
		var excerpts = EvidenceExcerpts.find(MEETING, List.of("CSV-08로 박종민이 23일 17시까지 수정한다.", "파일을 생성한다. 재현 로그와"));

		assertThat(excerpts).hasSize(1);
		assertThat(excerpts.get(0).quotes()).hasSize(2);
	}

	@Test
	void 원문에_없는_근거나_빈_회의록은_비워_둔다() {
		assertThat(EvidenceExcerpts.find(MEETING, List.of("회의에 없던 문장"))).isEmpty();
		assertThat(EvidenceExcerpts.find("", List.of("허민규가 재현했다."))).isEmpty();
		assertThat(EvidenceExcerpts.find(MEETING, List.of())).isEmpty();
	}

	@Test
	void 너무_긴_단락은_근거_주변만_남긴다() {
		String longText = "가".repeat(2000) + " 핵심 결정 문장이다. " + "나".repeat(2000);
		var excerpts = EvidenceExcerpts.find(longText, List.of("핵심 결정 문장이다."));

		assertThat(excerpts.get(0).heading()).isNull();
		assertThat(excerpts.get(0).text()).contains("핵심 결정 문장이다.").startsWith("…").endsWith("…")
				.hasSizeLessThan(EvidenceExcerpts.MAX_TEXT);
	}
}
