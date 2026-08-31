package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.FunctionUsageSummary;
import cn.wayuwa.wao.dto.FunctionIterationInput;
import cn.wayuwa.wao.mapper.FunctionIterationMapper;
import cn.wayuwa.wao.mapper.FunctionProductMapper;
import cn.wayuwa.wao.mapper.FunctionUsageEventMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FunctionHealthServiceTest {
    private final FunctionProductMapper products = mock(FunctionProductMapper.class);
    private final FunctionUsageEventMapper usage = mock(FunctionUsageEventMapper.class);
    private final FunctionIterationMapper iterations = mock(FunctionIterationMapper.class);
    private final FunctionHealthService service = new FunctionHealthService(products, usage, iterations);

    @Test
    void emptySnapshotUsesDefaultOneWithoutInventingUsage() {
        when(usage.summarize(eq("member-a"), any())).thenReturn(List.of());
        when(products.selectBatchIds(any())).thenReturn(List.of());

        var overview = service.overview("member-a");

        assertThat(overview.recentOpens()).isZero();
        assertThat(overview.functions()).extracting("featureId").contains("crawler-capabilities");
        assertThat(overview.functions()).allSatisfy(item -> {
            assertThat(item.score()).isEqualTo(1);
            assertThat(item.recentOpens()).isZero();
            assertThat(item.insightLevel()).isEqualTo("UNKNOWN");
        });
        verify(usage).summarize(eq("member-a"), any(LocalDateTime.class));
    }

    @Test
    void memberUsageCreatesGroundedLowAndHighPriorityHints() {
        FunctionUsageSummary navigation = summary("navigation", 40, 40);
        FunctionUsageSummary projects = summary("projects", 1, 1);
        when(usage.summarize(eq("member-a"), any())).thenReturn(List.of(navigation, projects));
        when(products.selectBatchIds(any())).thenReturn(List.of());

        var overview = service.overview("member-a");

        assertThat(overview.recentOpens()).isEqualTo(41);
        assertThat(item(overview, "navigation").insightLevel()).isEqualTo("HIGH");
        assertThat(item(overview, "projects").insightLevel()).isEqualTo("LOW");
        assertThat(item(overview, "navigation").recentShare()).isBetween(97.5, 97.6);
    }

    @Test
    void onlyKnownSuccessfulCommitScopesCanIncrementAndDuplicateIsIdempotent() {
        when(iterations.insertIgnore("run-1", "navigation", "abc", "feat(navigation): 改进工作台"))
                .thenReturn(1).thenReturn(0);
        var inputs = List.of(
                new FunctionIterationInput("abc", "navigation", "feat(navigation): 改进工作台"),
                new FunctionIterationInput("def", "deploy", "fix(deploy): 调整发布"));

        service.recordIterations("run-1", inputs);
        service.recordIterations("run-1", inputs);

        verify(products).incrementIteration("navigation");
        verify(products, never()).incrementIteration("deploy");
    }

    @Test
    void crawlerScopeMapsToCrawlerCapabilityFeature() {
        when(iterations.insertIgnore("run-crawler", "crawler-capabilities", "crawler-sha",
                "feat(crawler): 新增爬虫能力管理")).thenReturn(1);

        service.recordIterations("run-crawler", List.of(new FunctionIterationInput(
                "crawler-sha", "crawler", "feat(crawler): 新增爬虫能力管理")));

        verify(products).incrementIteration("crawler-capabilities");
    }

    @Test
    void manualAdjustmentOnlyAcceptsOnePoint() {
        assertThatThrownBy(() -> service.adjust("member-a", "navigation", 2))
                .hasMessageContaining("只能调整 1 分");
    }

    private FunctionUsageSummary summary(String id, long total, long recent) {
        FunctionUsageSummary result = new FunctionUsageSummary();
        result.setFeatureId(id);
        result.setTotalOpens(total);
        result.setRecentOpens(recent);
        result.setLastOpenedAt(LocalDateTime.now());
        return result;
    }

    private cn.wayuwa.wao.dto.FunctionHealthItemDto item(
            cn.wayuwa.wao.dto.FunctionHealthOverviewDto overview, String id) {
        return overview.functions().stream().filter(item -> item.featureId().equals(id)).findFirst().orElseThrow();
    }
}
