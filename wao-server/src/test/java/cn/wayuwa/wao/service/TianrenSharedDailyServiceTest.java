package cn.wayuwa.wao.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class TianrenSharedDailyServiceTest {
    @Test void generatesOnlyAfterEightAndKeepsOneSharedResult() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:shared-reading;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE tianren_shared_daily_reading (reading_date DATE PRIMARY KEY, lines_json VARCHAR(32), created_at TIMESTAMP)");
        var engine = new TianrenHexagramEngine();
        var json = new ObjectMapper();
        var before = new TianrenSharedDailyService(jdbc, engine, json, Clock.fixed(Instant.parse("2026-11-09T23:59:00Z"), ZoneOffset.UTC));
        before.generateDue();
        assertThat(before.today()).isNull();
        var after = new TianrenSharedDailyService(jdbc, engine, json, Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC));
        after.generateDue();
        var first = after.today();
        after.generateDue();
        assertThat(after.today()).isEqualTo(first);
        assertThat(first.date()).isEqualTo(LocalDate.parse("2026-11-10"));
        assertThat(first.conversationId()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tianren_shared_daily_reading", Integer.class)).isEqualTo(1);
    }
}
