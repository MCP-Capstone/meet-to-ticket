package com.mcpcapstone.meettoticket.meeting;

import static org.assertj.core.api.Assertions.*;

import com.mcpcapstone.meettoticket.common.error.BusinessException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class MeetingParserTest {
    private final MeetingParser parser = new MeetingParser();

    @Test
    void markdown요약본의_날짜와_참여자를_읽고_본문을_그대로_보존한다() {
        String text =
                "# 회의 요약 — CSV\r\n\r\n"
                        + "- 회의 날짜: 2026-10-22 10:00~10:30\r\n"
                        + "- 회의 참여자: 박종민, 허민규\r\n\r\n"
                        + "## [구간 1]\r\n"
                        + "업무 내용\r\n";
        var parsed = parser.parse(("\uFEFF" + text).getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.title()).isEqualTo("회의 요약 — CSV");
        assertThat(parsed.date()).hasToString("2026-10-22");
        assertThat(parsed.participantNames()).containsExactly("박종민", "허민규");
        assertThat(parsed.content()).isEqualTo(text);
    }

    @Test
    void 잘못된_UTF8을_조용히_치환하지_않는다() {
        assertThatThrownBy(() -> parser.parse(new byte[] {(byte) 0xc3, 0x28}))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 날짜가_없으면_오늘날짜를_사용하지_않는다() {
        assertThatThrownBy(() -> parser.parse("# CSV\n참여자: 박종민".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 날짜가_잘못되면_거절한다() {
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        "# CSV\n회의 날짜: 2026-02-30\n참여자: 박종민"
                                                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
    }
}
