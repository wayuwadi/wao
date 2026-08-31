package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 撤回状态机的注解 SQL 必须保留墓碑优先和目标补录条件。 */
class FeishuQuickCollectEventMapperSqlTest {

    private static Configuration mybatis;

    @BeforeAll
    static void parseMapperAnnotations() {
        mybatis = new Configuration();
        mybatis.addMapper(FeishuQuickCollectEventMapper.class);
    }

    @Test
    void normalCompletionCannotOverwriteARecallTombstone() {
        String sql = sql("finishUnlessRecalled", Map.of(
                "eventKey", "message:om_1", "status", "CREATED", "kind", "TASK",
                "targetId", "t1", "fingerprint", "a".repeat(64), "error", "",
                "updatedAt", LocalDateTime.now()));

        assertThat(sql)
                .contains("target_id = COALESCE(?, target_id)")
                .contains("source_fingerprint = COALESCE(?, source_fingerprint)")
                .contains("WHERE event_key = ? AND status NOT IN ('RECALLING', 'RECALLED')");
    }

    @Test
    void lateTargetAttachmentKeepsTheRecallState() {
        String sql = sql("attachRecalledTarget", Map.of(
                "eventKey", "message:om_1", "targetId", "t1",
                "fingerprint", "a".repeat(64), "updatedAt", LocalDateTime.now()));

        assertThat(sql).contains("target_id = COALESCE(target_id, ?)")
                .contains("WHERE event_key = ? AND status IN ('RECALLING', 'RECALLED')")
                .doesNotContain("SET status");
    }

    private static String sql(String method, Map<String, Object> parameters) {
        return mybatis.getMappedStatement(FeishuQuickCollectEventMapper.class.getName() + "." + method)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }
}
