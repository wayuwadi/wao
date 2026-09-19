package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.ArrayList;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式启用后复用现有 MySQL，仅执行 CTE SELECT；不创建表、不插入业务记录、不运行迁移。 */
@EnabledIfEnvironmentVariable(named = "WAO_PERFORMANCE_MYSQL_READ_ONLY", matches = "true")
class DocBacklinkMySqlReadOnlyTest {
    @Test
    void backlinkQueryMatchesWholeLinkObjectsAndPreservesLifecycleRules() throws Exception {
        String sql = String.join("\n", DocMapper.class.getMethod("selectBacklinkSources", String.class)
                .getAnnotation(Select.class).value()).replace("#{id}", "?");
        String fixture = """
                WITH doc AS (
                  SELECT 'valid' id, '来源' title, '[]' blocks_json, '' content_markdown,
                    CAST('[{"type":"doc","id":"target"},{"type":"doc","id":"target"}]' AS JSON) links_json,
                    NULL deleted_at, NULL archived_at
                  UNION ALL SELECT 'target', '自己', '[]', '', JSON_ARRAY(JSON_OBJECT('type','doc','id','target')), NULL, NULL
                  UNION ALL SELECT 'wrong-type', '其它类型', '[]', '', JSON_ARRAY(JSON_OBJECT('type','task','id','target')), NULL, NULL
                  UNION ALL SELECT 'split', '分开的键', '[]', '', JSON_ARRAY(JSON_OBJECT('type','doc','id','else'),JSON_OBJECT('type','task','id','target')), NULL, NULL
                  UNION ALL SELECT 'deleted', '已删除', '[]', '', JSON_ARRAY(JSON_OBJECT('type','doc','id','target')), CURRENT_TIMESTAMP, NULL
                  UNION ALL SELECT 'archived', '已归档', '[]', '', JSON_ARRAY(JSON_OBJECT('type','doc','id','target')), NULL, CURRENT_TIMESTAMP
                  UNION ALL SELECT 'empty', '空链接', '[]', '', NULL, NULL, NULL
                )
                """;
        String url = "jdbc:mysql://" + System.getenv("MYSQL_HOST") + ":"
                + System.getenv().getOrDefault("MYSQL_PORT", "3306") + "/" + System.getenv("DB_NAME")
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        try (var connection = DriverManager.getConnection(url, System.getenv("DB_USER"), System.getenv("DB_PASS"))) {
            connection.setReadOnly(true);
            try (var query = connection.prepareStatement(fixture + sql)) {
                query.setString(1, "target"); query.setString(2, "target");
                var ids = new ArrayList<String>();
                try (var rows = query.executeQuery()) { while (rows.next()) ids.add(rows.getString("id")); }
                assertThat(ids).containsExactlyInAnyOrder("valid", "archived");
            }
        }
    }
}
