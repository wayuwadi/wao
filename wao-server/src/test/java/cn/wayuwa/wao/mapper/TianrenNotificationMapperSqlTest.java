package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import cn.wayuwa.wao.domain.TianrenNotificationEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tianrennotification;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql(statements = {
        "DROP TABLE IF EXISTS tianren_notification",
        "DROP TABLE IF EXISTS tianren_daily_reading",
        "CREATE TABLE tianren_daily_reading (id VARCHAR(32) PRIMARY KEY, reading_date DATE, conversation_id VARCHAR(32))",
        "INSERT INTO tianren_daily_reading (id) VALUES ('tr-one')"
})
@Sql(scripts = "/db/migration/V140__tianren_notification.sql")
class TianrenNotificationMapperSqlTest {
    @Autowired TianrenNotificationMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test void duplicateInsertAndCompareAndSetCannotReclaimSentCard() {
        LocalDateTime now = LocalDateTime.now();
        var row = new TianrenNotificationEntity("tr-one", "PENDING", "CHAT", "oc-group", "群", "{}", null, "等待", now);
        assertThat(mapper.insert(row)).isEqualTo(1);
        assertThat(mapper.insert(row)).isEqualTo(0);
        assertThat(mapper.find("tr-one").targetName()).isEqualTo("群");
        assertThat(mapper.pending()).hasSize(1);
        assertThat(mapper.claim("tr-one", "attempt-a", now)).isEqualTo(1);
        assertThat(mapper.claim("tr-one", "attempt-b", now)).isEqualTo(0);
        assertThat(mapper.finish("tr-one", "attempt-b", "SENT", "迟到", "om-wrong", now)).isEqualTo(0);
        assertThat(mapper.finish("tr-one", "attempt-a", "SENT", "成功", "om-message", now)).isEqualTo(1);
        assertThat(mapper.find("tr-one").messageId()).isEqualTo("om-message");
        assertThat(mapper.transition("tr-one", "PENDING", "SENDING", "发送中", null, now)).isEqualTo(0);
        assertThat(mapper.pending()).isEmpty();
    }

    @Test void crashedSendingBecomesUnknownNotPending() {
        LocalDateTime now = LocalDateTime.now();
        mapper.insert(new TianrenNotificationEntity("tr-one", "SENDING", "WEBHOOK", "wh-one", "群", "{}", null, "", now.minusMinutes(6)));
        assertThat(mapper.expire(now, now.minusMinutes(5))).isEqualTo(1);
        assertThat(mapper.find("tr-one").state()).isEqualTo("UNKNOWN");
        assertThat(mapper.pending()).isEmpty();
    }
}
