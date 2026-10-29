package cn.wayuwa.wao.mapper;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.assertThat;

class PersonalRoutineNotificationQueryTest {
    @Test void readsOnlyCurrentMembersExplicitRoutineReminders() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:notification-"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE internal_notification(id VARCHAR(64),member_id VARCHAR(64),title TEXT,body TEXT,target_path TEXT,created_at TIMESTAMP)");
        jdbc.update("INSERT INTO internal_notification VALUES ('date','me','纪念日','今天','/countdowns',CURRENT_TIMESTAMP),('habit','me','喝水','三杯','/habits?habit=abc',CURRENT_TIMESTAMP),('other','other','别人的','私有','/countdowns',CURRENT_TIMESTAMP),('group',NULL,'组织','旧通知','/countdowns',CURRENT_TIMESTAMP),('legacy','me','旧通知','旧通知','/projects/p',CURRENT_TIMESTAMP)");
        var ids = jdbc.query(NotificationCenterMapper.PERSONAL_ROUTINE_EVENTS.replace("#{memberId}", "?"), (rs,n)->rs.getString("event_id"), "me");
        assertThat(ids).containsExactlyInAnyOrder("routine:date", "routine:habit");
    }
}
