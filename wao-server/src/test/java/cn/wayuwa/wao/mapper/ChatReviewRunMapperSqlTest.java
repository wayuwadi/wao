package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 防止注解 SQL 把比较符以 XML 实体原样发给 MySQL。 */
class ChatReviewRunMapperSqlTest {

    private static Configuration mybatis;

    @BeforeAll
    static void parseMapperAnnotations() {
        mybatis = new Configuration();
        mybatis.addMapper(ChatReviewRunMapper.class);
    }

    @Test
    void leaseComparisonsProduceExecutableSqlOperators() {
        String claim = sql("claimLease", Map.of(
                "id", "cr1", "workerToken", "worker"));
        String link = sql("linkAiRun", Map.of(
                "id", "cr1", "workerToken", "worker", "aiRunId", "ac1"));

        assertThat(claim).contains("lease_until < CURRENT_TIMESTAMP(6)").doesNotContain("&lt;");
        assertThat(link).contains("lease_until > CURRENT_TIMESTAMP(6)").doesNotContain("&gt;");
    }

    private static String sql(String method, Map<String, Object> parameters) {
        return mybatis.getMappedStatement(ChatReviewRunMapper.class.getName() + "." + method)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
    }
}
