package com.mcpcapstone.meettoticket.analysis;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 근거 문장이 들어 있는 회의록 원문 단락을 찾는다.
 * 회의록 전체는 공개 응답에 넣지 않으므로, 티켓 근거와 관련된 단락만 잘라 보여 준다.
 * 단락은 빈 줄로 나눈 덩어리이고, 바로 앞의 '#' 제목(예: "## [구간 2] 수정 합의")을 함께 붙인다.
 */
public final class EvidenceExcerpts {
	// 단락이 지나치게 길면(제목 없이 한 덩어리인 회의록 등) 근거 문장 주변만 남긴다.
	static final int MAX_TEXT = 1200;
	private static final int AROUND = 300;

	private EvidenceExcerpts() {}

	@Schema(description = "근거 문장이 들어 있는 회의록 원문 단락")
	public record Excerpt(
			@Schema(description = "단락 바로 앞의 회의록 제목. 없으면 null", nullable = true, example = "[구간 2] 수정 합의와 완료 조건") String heading,
			@Schema(description = "회의록 원문 단락", example = "CSV-08로 박종민이 23일 17시까지 수정한다.") String text,
			@Schema(description = "이 단락에 들어 있는 근거 문장") List<String> quotes) {}

	static List<Excerpt> find(String content, List<String> quotes) {
		if (content == null || content.isBlank() || quotes.isEmpty()) return List.of();
		List<Block> blocks = blocks(content);
		Map<Block, List<String>> found = new LinkedHashMap<>();
		for (String quote : quotes) {
			String needle = squash(quote);
			if (needle.isEmpty()) continue;
			// 근거는 원문 그대로지만 여러 줄에 걸칠 수 있어 공백을 하나로 맞춰 찾는다.
			blocks.stream().filter(b -> squash(b.text).contains(needle)).findFirst()
					.ifPresent(b -> found.computeIfAbsent(b, k -> new ArrayList<>()).add(quote));
		}
		// 회의록 순서대로 보여 준다.
		return blocks.stream().filter(found::containsKey)
				.map(b -> new Excerpt(b.heading, trim(b.text, found.get(b)), found.get(b))).toList();
	}

	private record Block(String heading, String text) {}

	private static List<Block> blocks(String content) {
		List<Block> out = new ArrayList<>();
		String heading = null;
		StringBuilder text = new StringBuilder();
		for (String line : content.replace("\r", "").split("\n", -1)) {
			String stripped = line.strip();
			if (stripped.startsWith("#")) {
				flush(out, heading, text);
				heading = stripped.replaceFirst("^#+\\s*", "");
			} else if (stripped.isEmpty()) {
				flush(out, heading, text);
			} else {
				if (!text.isEmpty()) text.append('\n');
				text.append(line.stripTrailing());
			}
		}
		flush(out, heading, text);
		return out;
	}

	private static void flush(List<Block> out, String heading, StringBuilder text) {
		if (!text.isEmpty()) out.add(new Block(heading, text.toString()));
		text.setLength(0);
	}

	private static String trim(String text, List<String> quotes) {
		if (text.length() <= MAX_TEXT) return text;
		int at = squashIndex(text, quotes.get(0));
		int start = Math.max(0, at - AROUND);
		int end = Math.min(text.length(), Math.max(at, 0) + quotes.get(0).length() + AROUND);
		return (start > 0 ? "…" : "") + text.substring(start, end) + (end < text.length() ? "…" : "");
	}

	// 공백을 맞춘 비교로 찾은 근거의 원문 위치. 찾지 못하면 단락 앞부분을 쓴다.
	private static int squashIndex(String text, String quote) {
		int direct = text.indexOf(quote);
		if (direct >= 0) return direct;
		String first = quote.strip().split("\\s+")[0];
		return Math.max(0, text.indexOf(first));
	}

	private static String squash(String value) {
		return value == null ? "" : value.strip().replaceAll("\\s+", " ");
	}
}
