package cn.wayuwa.wao;

import static org.assertj.core.api.Assertions.assertThat;
import cn.wayuwa.wao.mapper.NotificationCenterMapper;
import java.sql.DriverManager;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 实际 MySQL 验证 @ 对象、项目权限和 AI 发起人过滤，避免 SQL 方言错误上线。 */
@Testcontainers(disabledWithoutDocker = true)
class NotificationCenterMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:9.7.0")
            .withConfigurationOverride("mysql-9-test-conf");

    @Test
    void onlyMentionsAndOwnFinishedRunsSurvive() throws Exception {
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var s = c.createStatement()) {
            s.execute("CREATE TABLE member (id VARCHAR(64), name VARCHAR(64))");
            s.execute("INSERT INTO member VALUES ('wg','娃哥'),('wd','娃弟')");
            s.execute("CREATE TABLE shared_chat_message (id VARCHAR(64), source VARCHAR(16), member_id VARCHAR(64), content TEXT, created_at DATETIME, deleted BOOLEAN)");
            s.execute("INSERT INTO shared_chat_message VALUES ('yes','WAO','wd','@娃哥 请看',NOW(),0),('self','WAO','wg','@娃哥 自己',NOW(),0),('other','WAO','wd','@娃哥哥',NOW(),0),('deleted','WAO','wd','@娃哥',NOW(),1),('normal','WAO','wd','普通聊天',NOW(),0)");
            s.execute("CREATE TABLE project (id VARCHAR(64), owner_id VARCHAR(64), detail_json JSON)");
            s.execute("INSERT INTO project VALUES ('private','wd','{\"members\":[]}'),('shared','wd','{\"members\":[\"wg\"]}')");
            s.execute("CREATE TABLE task (id VARCHAR(64), title TEXT, project_id VARCHAR(64), detail_json JSON)");
            s.execute("INSERT INTO task VALUES ('hidden','隐藏任务','private','{\"comments\":[{\"who\":\"wd\",\"time\":\"2026-09-10T12:00:00+08:00\",\"text\":\"@娃哥 私有\"}]}'),('visible','可见任务','shared','{\"comments\":[{\"who\":\"wd\",\"time\":\"2026-09-10T12:00:00+08:00\",\"text\":\"@娃哥 请看\"}]}')");
            s.execute("CREATE TABLE ai_task_run (id VARCHAR(64), task_id VARCHAR(64), project_id VARCHAR(64), requested_by VARCHAR(64), status VARCHAR(32), finished_at DATETIME)");
            s.execute("INSERT INTO ai_task_run VALUES ('own','visible','shared','wg','succeeded',NOW()),('others','visible','shared','wd','succeeded',NOW()),('running','visible','shared','wg','running',NULL)");
            s.execute("CREATE TABLE internal_notification (id VARCHAR(64),member_id VARCHAR(64),title TEXT,body TEXT,target_path TEXT,created_at DATETIME)");
            s.execute("INSERT INTO internal_notification VALUES ('date','wg','日期','提醒','/countdowns',NOW()),('habit','wd','习惯','提醒','/habits?habit=x',NOW()),('group',NULL,'组织','旧提醒','/countdowns',NOW())");
            try (var p = c.prepareStatement(NotificationCenterMapper.EVENT_UNION.replace("#{memberId}", "?"))) {
                for (int i = 1; i <= p.getParameterMetaData().getParameterCount(); i++) p.setString(i, "wg");
                var ids = new ArrayList<String>();
                try (var r = p.executeQuery()) { while (r.next()) ids.add(r.getString("event_id")); }
                assertThat(ids).containsExactlyInAnyOrder("mention-chat:yes", "mention-comment:visible:1", "ai-run:own", "routine:date");
            }
        }
    }
}
