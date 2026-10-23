package com.mcpcapstone.meettoticket.meeting;

import com.mcpcapstone.meettoticket.common.error.*;

import org.springframework.stereotype.Component;

import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

@Component
public class MeetingParser {
    private static final Pattern TITLE =
            Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?(?:회의\\s*)?(?:제목|회의명|title)\\s*[:：]\\s*(.+)$");
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,2}\\s+(.+)$");
    private static final Pattern DATE =
            Pattern.compile(
                    "(?m)^\\s*(?:[-*]\\s*)?(?:회의\\s*(?:날짜|일시)|날짜|일시|meetingDate)\\s*[:：]\\s*(\\d{4}-\\d{2}-\\d{2})(?:\\s.*)?$");
    private static final Pattern PEOPLE =
            Pattern.compile("(?m)^\\s*(?:[-*]\\s*)?(?:회의\\s*)?(?:참여자|참석자)\\s*[:：]\\s*(.+)$");

    /** UTF-8 본문. 최초 BOM은 빼고, 비었거나 NUL이 있거나 UTF-8이 아니면 비어 있다. */
    public Optional<String> decode(byte[] bytes) {
        try {
            String content =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            if (content.isBlank() || content.indexOf('\0') >= 0) return Optional.empty();
            return Optional.of(content);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** 첫 #, ## 제목줄. */
    public Optional<String> heading(String content) {
        Matcher h = HEADING.matcher(content);
        return h.find() ? Optional.of(h.group(1).trim()) : Optional.empty();
    }

    public Parsed parse(byte[] bytes) {
        String content = decode(bytes).orElseThrow(this::invalid);
        try {
            Matcher t = TITLE.matcher(content),
                    d = DATE.matcher(content),
                    p = PEOPLE.matcher(content);
            String title;
            if (t.find()) title = t.group(1).trim();
            else title = heading(content).orElseThrow(this::invalid);
            if (title.isBlank() || title.length() > 255 || !d.find() || !p.find()) throw invalid();
            LocalDate date = LocalDate.parse(d.group(1));
            List<String> names =
                    Arrays.stream(p.group(1).split("[,，、]"))
                            .map(String::trim)
                            .filter(n -> !n.isBlank())
                            .toList();
            if (names.isEmpty() || new HashSet<>(names).size() != names.size()) throw invalid();
            return new Parsed(title, date, names, content);
        } catch (DateTimeException e) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_MEETING_FILE);
    }

    public record Parsed(
            String title, LocalDate date, List<String> participantNames, String content) {}
}
