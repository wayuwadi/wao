package cn.wayuwa.wao.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.HttpRequestMetricEntity;
import java.time.LocalDateTime;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证批量绑定、NULL、自动 id 和文档版本的实际持久化语义。JSON 查询另用 MySQL 只读验收。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:performancemappers;DB_CLOSE_DELAY=-1;MODE=MySQL")
class PerformanceMapperIntegrationTest {
    @Autowired private HttpRequestMetricMapper metrics;
    @Autowired private DocMapper docs;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void batchPersistsEveryMetricAndKeepsNullableNetworkFailures() {
        jdbc.execute("""
                CREATE TABLE http_request_metric (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id VARCHAR(32), method VARCHAR(8),
                  request_path VARCHAR(255), status_code INT, duration_ms INT, network_type VARCHAR(32),
                  retry_attempt INT, started_at TIMESTAMP, created_at TIMESTAMP)
                """);
        var now = LocalDateTime.of(2026, 9, 19, 9, 0);
        var batch = IntStream.range(0, 100).mapToObj(index -> {
            var metric = new HttpRequestMetricEntity();
            metric.setMemberId("member-a"); metric.setMethod("GET");
            metric.setRequestPath("/api/tasks"); metric.setDurationMs(index);
            metric.setStatusCode(index == 0 ? null : 200); metric.setRetryAttempt(index % 4);
            metric.setStartedAt(now); metric.setCreatedAt(now);
            return metric;
        }).toList();
        assertThat(metrics.insertBatch(batch)).isEqualTo(100);
        var saved = metrics.listOwnedSince("member-a", now.minusSeconds(1));
        assertThat(saved).hasSize(100);
        assertThat(saved).extracting(HttpRequestMetricEntity::getId).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(saved).extracting(HttpRequestMetricEntity::getDurationMs)
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, 100).boxed().toList());
        assertThat(saved.stream().filter(item -> item.getStatusCode() == null).count()).isEqualTo(1);
        assertThat(metrics.listOwnedSince("member-b", now.minusSeconds(1))).isEmpty();
    }

    @Test
    void revisionQueryDistinguishesLegacyMissingAndSoftDeletedDocumentsWithoutBodyColumns() {
        jdbc.execute("CREATE TABLE doc (id VARCHAR(32) PRIMARY KEY, rev INT, deleted_at TIMESTAMP)");
        jdbc.update("INSERT INTO doc VALUES ('legacy', NULL, NULL), ('current', 7, NULL), ('deleted', 8, CURRENT_TIMESTAMP)");
        assertThat(docs.selectAliveRevision("legacy")).isEqualTo(1);
        assertThat(docs.selectAliveRevision("current")).isEqualTo(7);
        assertThat(docs.selectAliveRevision("deleted")).isNull();
        assertThat(docs.selectAliveRevision("missing")).isNull();
    }
}
