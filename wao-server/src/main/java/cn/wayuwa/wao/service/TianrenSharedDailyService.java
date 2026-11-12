package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.TianrenReadingDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One shared cast per Shanghai day, independent of members' private readings. */
@Service
public class TianrenSharedDailyService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final TianrenHexagramEngine engine;
    private final ObjectMapper json;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public TianrenSharedDailyService(JdbcTemplate jdbc, TianrenHexagramEngine engine, ObjectMapper json) {
        this(jdbc, engine, json, Clock.system(ZONE));
    }
    TianrenSharedDailyService(JdbcTemplate jdbc, TianrenHexagramEngine engine, ObjectMapper json, Clock clock) {
        this.clock = clock;
        this.jdbc = jdbc;
        this.engine = engine;
        this.json = json;
    }

    /** Retry missed runs after restart; the primary key resolves concurrent schedulers. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void generateDue() {
        LocalDateTime now = LocalDateTime.now(clock.withZone(ZONE));
        if (now.toLocalTime().isBefore(LocalTime.of(8, 0))) return;
        LocalDate date = now.toLocalDate();
        if (read(date) != null) return;
        try {
            jdbc.update("INSERT INTO tianren_shared_daily_reading (reading_date, lines_json, created_at) VALUES (?, ?, ?)",
                    date, json.writeValueAsString(engine.castLines()), now);
        } catch (DuplicateKeyException ignored) {
            // Another instance generated today's result.
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("今日卦象保存失败", e);
        }
    }

    public TianrenReadingDto today() { return read(LocalDate.now(clock.withZone(ZONE))); }

    private TianrenReadingDto read(LocalDate date) {
        var rows = jdbc.query("SELECT lines_json, created_at FROM tianren_shared_daily_reading WHERE reading_date = ?",
                (rs, n) -> {
                    try {
                        List<Integer> lines = json.readValue(rs.getString(1), new TypeReference<List<Integer>>() {});
                        var reading = engine.describe(lines);
                        return new TianrenReadingDto("shared-" + date, date, reading.lines(), reading.changingLines(),
                                reading.primary(), reading.relating(), null, rs.getTimestamp(2).toLocalDateTime());
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException("今日卦象读取失败", e);
                    }
                }, date);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
