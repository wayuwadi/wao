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
        assertThat(overview.functions()).extracting("featureId")
                .contains("projects", "automation")
                .doesNotContain("crawler-capabilities", "account-resources", "infrastructure");
        assertThat(overview.functions()).allSatisfy(item -> {
            assertThat(item.score()).isEqualTo(1);
            assertThat(item.recentOpens()).isZero();
            assertThat(item.insightLevel()).isEqualTo("UNKNOWN");
            assertThat(item.insight()).contains("暂不建议下线");
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
        assertThat(item(overview, "projects").insight()).contains("优化入口", "是否合并或下线");
        assertThat(item(overview, "navigation").insight()).contains("缩短高频操作路径");
        assertThat(item(overview, "navigation").recentShare()).isBetween(97.5, 97.6);
    }

    @Test
    void zeroRecentUsageRecommendsPersonalHideAndCrossMemberReviewBeforeSunset() {
        when(usage.summarize(eq("member-a"), any())).thenReturn(List.of(summary("projects", 12, 0)));
        when(products.selectBatchIds(any())).thenReturn(List.of());

        var item = item(service.overview("member-a"), "projects");

        assertThat(item.insightLevel()).isEqualTo("LOW");
        assertThat(item.insight()).contains("从个人导航移除", "核对其他成员使用和业务依赖");
    }

    @Test
    void oneHighUsageFeatureDoesNotMakeEveryOtherFeatureLookLow() {
        when(usage.summarize(eq("member-a"), any())).thenReturn(List.of(
                summary("home", 20_000, 20_000), summary("calendar", 5, 5),
                summary("games", 4, 4), summary("projects", 3, 3)));
        when(products.selectBatchIds(any())).thenReturn(List.of());

        var item = item(service.overview("member-a"), "projects");

        assertThat(item.insightLevel()).isEqualTo("NORMAL");
        assertThat(item.insight()).contains("保持观察");
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
