package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.RunnerMemoryReportItem;
import cn.wayuwa.wao.dto.RunnerMemoryReportRequest;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

/** 验证真实表上的自动候选、幂等更新、权限范围和失败状态。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:runnermemory;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Sql({"/unified-memory-schema.sql", "/runner-memory-collection-schema.sql"})
class RunnerMemoryCollectionServiceIntegrationTest {
    @Autowired private RunnerMemoryCollectionService collections;
    @Autowired private UnifiedMemoryService memories;
    @Autowired private AiRunnerNodeMapper runners;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void createsReviewableMachineAndApplicationCandidatesAndSkipsUnchangedReports() {
        AiRunnerNodeEntity runner = runners.selectById("rn1");
        RunnerMemoryReportRequest first = new RunnerMemoryReportRequest(List.of(
                report("machine", "", "operation", "开发电脑的稳定维护方法", "每天检查 Runner 心跳和脚本版本，异常时保留日志再重试。"),
                report("application", "codex", "convention", "Codex 的长期工作约定", "完成任务前执行真实测试，发布后继续跟踪部署和线上验收结果。")), null);

        assertThat(collections.collect(runner, first)).hasSize(2)
                .allMatch(item -> "draft".equals(item.revision().state()));
        assertThat(collections.collect(runner, first)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_revision", Integer.class)).isEqualTo(2);

        RunnerMemoryReportRequest changed = new RunnerMemoryReportRequest(List.of(
                report("machine", "", "operation", "开发电脑的稳定维护方法", "每天检查 Runner 心跳、磁盘和脚本版本，异常时保留日志再重试。")), null);
        assertThat(collections.collect(runner, changed)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_revision", Integer.class)).isEqualTo(3);

        var candidate = memories.list("wg", "machine", "rn1", null, null, "draft").get(0);
        memories.publish("wg", candidate.revision().id());
        assertThat(memories.searchPublished("wg", "machine", "rn1", "磁盘")).hasSize(1);
        assertThat(memories.list("wg", "application", "codex", null, null, "draft")).hasSize(1);
    }

    @Test
    void recordsMissingSourceWithoutCreatingPlaceholderDocument() {
        collections.collect(runners.selectById("rn1"), new RunnerMemoryReportRequest(List.of(),
                "未发现 CODEX_HOME/memories"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM memory_document", Integer.class)).isZero();
        assertThat(runners.selectById("rn1").getLastMemoryCollectionError()).contains("未发现");
    }

    private static RunnerMemoryReportItem report(String scope, String scopeId, String category,
            String title, String content) {
        return new RunnerMemoryReportItem(scope, scopeId, category, title, content);
    }
}
