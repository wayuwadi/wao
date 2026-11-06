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
        jdbc.update("INSERT INTO internal_notification VALUES ('task','me','任务提醒','本人执行安排','/todo?task=t1',CURRENT_TIMESTAMP),('task-other','other','别人的任务','私有','/todo?task=t2',CURRENT_TIMESTAMP),('task-group',NULL,'组织任务','旧通知','/todo?task=t3',CURRENT_TIMESTAMP),('task-empty','me','空任务','无跳转目标','/todo?task=',CURRENT_TIMESTAMP)");
        var ids = jdbc.query(NotificationCenterMapper.PERSONAL_ROUTINE_EVENTS.replace("#{memberId}", "?"), (rs,n)->rs.getString("event_id"), "me");
        assertThat(ids).containsExactlyInAnyOrder("routine:date", "routine:habit", "routine:task");
        var others = jdbc.query(NotificationCenterMapper.PERSONAL_ROUTINE_EVENTS.replace("#{memberId}", "?"), (rs,n)->rs.getString("event_id"), "other");
        assertThat(others).containsExactlyInAnyOrder("routine:other", "routine:task-other");
    }
}
