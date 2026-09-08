package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.domain.ProductUpdateRow;
import cn.wayuwa.wao.dto.FunctionIterationInput;
import cn.wayuwa.wao.mapper.ProductUpdateMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class UpdateDiaryServiceTest {
    private final ProductUpdateMapper updates = mock(ProductUpdateMapper.class);
    private final UpdateDiaryService service = new UpdateDiaryService(updates);

    @Test
    void groupsVerifiedUpdatesByNonEmptyDayAndHidesCommitSyntax() {
        LocalDate newest = LocalDate.of(2026, 9, 8);
        LocalDate oldest = LocalDate.of(2026, 9, 6);
        when(updates.countDays()).thenReturn(2L);
        when(updates.selectDays(0, 12)).thenReturn(List.of(newest, oldest));
        when(updates.selectBetween(oldest, newest)).thenReturn(List.of(
                row("updates", "feat(updates): 新增更新日记 (#614)", "2026-09-08T10:30:00"),
                row("notes", "fix(notes): 修复小记保存失败", "2026-09-08T09:00:00"),
                row("home", "perf(home): 缩短首页加载时间", "2026-09-06T18:00:00")));

        var result = service.page(1, 12);

        assertThat(result.totalDays()).isEqualTo(2);
        assertThat(result.days()).hasSize(2);
        assertThat(result.days().get(0).date()).isEqualTo(newest);
        assertThat(result.days().get(0).entries())
                .extracting(entry -> entry.kind() + ":" + entry.title())
                .containsExactly("FEATURE:新增更新日记", "FIX:修复小记保存失败");
        assertThat(result.days().get(1).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.scope()).isEqualTo("home");
            assertThat(entry.kind()).isEqualTo("PERFORMANCE");
            assertThat(entry.title()).isEqualTo("缩短首页加载时间");
        });
    }

    @Test
    void returnsRealEmptyStateAndNormalizesPaging() {
        when(updates.countDays()).thenReturn(0L);
        when(updates.selectDays(0, 31)).thenReturn(List.of());

        var result = service.page(0, 99);

        assertThat(result.days()).isEmpty();
        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(31);
        verify(updates).selectDays(0, 31);
    }

    @Test
    void recordsConcreteGitProductCommitsAndSkipsEngineeringNoise() {
        service.recordVerifiedCommits("run-1", List.of(
                new FunctionIterationInput("agent-sha", "agent", "feat(agent): 增加手机预览",
                        "2026-09-08T10:30:00+08:00"),
                new FunctionIterationInput("deploy-sha", "deploy", "fix(deploy): 修复发布连接",
                        "2026-09-08T10:20:00+08:00"),
                new FunctionIterationInput("docs-sha", "delivery", "docs(delivery): 补充说明",
                        "2026-09-08T10:10:00+08:00"),
                new FunctionIterationInput("undated-sha", "notes", "fix(notes): 缺少提交时间", null)));

        verify(updates).insertIgnore("run-1", "agent-sha", "agent", "feat(agent): 增加手机预览",
                LocalDateTime.parse("2026-09-08T10:30:00"));
        verify(updates, org.mockito.Mockito.never()).insertIgnore(
                org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq("deploy-sha"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(updates, org.mockito.Mockito.never()).insertIgnore(
                org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.eq("undated-sha"),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    private static ProductUpdateRow row(String scope, String subject, String committedAt) {
        ProductUpdateRow row = new ProductUpdateRow();
        row.setScope(scope);
        row.setCommitSubject(subject);
        row.setCommittedAt(LocalDateTime.parse(committedAt));
        return row;
    }
}
