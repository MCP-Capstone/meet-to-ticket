package com.mcpcapstone.meettoticket.common.config;

import com.mcpcapstone.meettoticket.analysis.TicketStatus;

import jakarta.persistence.EntityManagerFactory;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

// Hibernate는 @Enumerated(STRING) 컬럼에 check (col in (...)) 제약을 만들지만, ddl-auto: update는 이미 있는 제약을 고치지 않는다.
// enum 값을 추가하면 기존 DB에서는 새 값을 저장할 때 제약 위반이 나므로, 시작할 때 제약을 enum 값에 맞춘다.
// 마이그레이션 도구를 들이기 전까지의 조치다. PostgreSQL에서만 한다.
@Slf4j
@Component
public class EnumCheckConstraintSync implements InitializingBean {
    private record Target(String table, String column, Class<? extends Enum<?>> type) {}

    // 기존 DB에 이미 있는 테이블에서 값이 늘어난 enum 컬럼
    private static final List<Target> TARGETS = List.of(new Target("task_items", "ticket_status", TicketStatus.class));

    private final JdbcTemplate jdbc;

    // EntityManagerFactory를 받아 Hibernate의 스키마 갱신이 끝난 뒤에 실행되게 한다.
    public EnumCheckConstraintSync(JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterPropertiesSet() {
        String product = jdbc.execute((Connection c) -> c.getMetaData().getDatabaseProductName());
        if (!"PostgreSQL".equals(product)) return;
        TARGETS.forEach(this::sync);
    }

    private void sync(Target t) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, t.table()))) return;
        List<String> values = Arrays.stream(t.type().getEnumConstants()).map(Enum::name).toList();
        var existing = jdbc.queryForList("""
                select conname, pg_get_constraintdef(oid) as def from pg_constraint
                 where conrelid = ?::regclass and contype = 'c' and pg_get_constraintdef(oid) like ?""",
                t.table(), "%" + t.column() + "%");
        boolean upToDate = !existing.isEmpty() && existing.stream()
                .allMatch(row -> values.stream().allMatch(v -> ((String) row.get("def")).contains("'" + v + "'")));
        if (upToDate) return;

        for (var row : existing)
            jdbc.execute("alter table " + t.table() + " drop constraint \"" + row.get("conname") + "\"");
        String in = values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
        jdbc.execute("alter table " + t.table() + " add constraint " + t.table() + "_" + t.column() + "_check"
                + " check (" + t.column() + " in (" + in + "))");
        log.info("{}.{} 체크 제약을 enum 값에 맞춤: {}", t.table(), t.column(), values);
    }
}
