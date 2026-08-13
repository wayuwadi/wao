package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiEvolutionStateEntity;
import cn.wayuwa.wao.domain.AiTaskRunEntity;
import cn.wayuwa.wao.dto.AiEvolutionOverviewDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWeeklyQuotaDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.mapper.AiEvolutionStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

class AiEvolutionServiceTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AiEvolutionStateEntity.class);
        TableInfoHelper.initTableInfo(assistant, AiTaskRunEntity.class);
    }

    private final AiAccountService accounts = mock(AiAccountService.class);
    private final AiRunService runs = mock(AiRunService.class);
    private final AiTaskRunMapper runMapper = mock(AiTaskRunMapper.class);
    private final AiEvolutionStateMapper stateMapper = mock(AiEvolutionStateMapper.class);
    private final AiEvolutionService service = new AiEvolutionService(
            accounts, runs, runMapper, stateMapper, "https://wao.example");

    @Test
    void overviewDoesNotTreatUnknownQuotaAsFullAllowance() {
        when(accounts.weeklyQuotas()).thenReturn(List.of());

        AiEvolutionOverviewDto overview = service.overview();

        assertThat(overview.weeklyQuotas()).isEmpty();
        assertThat(overview.autoStatus()).isEqualTo("quota_unavailable");
        assertThat(overview.autoMessage()).contains("尚未获取").contains("暂停");
        assertThat(overview.suggestions()).isNotEmpty();
    }

    @Test
    void idleSystemWithFreshRealQuotaLaunchesExactlyOneSuggestion() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(accounts.weeklyQuotas()).thenReturn(List.of(new AiWeeklyQuotaDto(
                "aa1", "codex", "Codex", "plus", 20, 80, "2026-08-17T10:00:00",
                LocalDateTime.now().toString(), "SYNCED", "七天额度已用 20%")));
        AiProjectConfigDto config = new AiProjectConfigDto("p1", true,
                "https://github.com/wayuwacn/wao.git", "main", "wao", "codex", null, 2);
        when(runs.listWorkflows()).thenReturn(List.of(new AiWorkflowDto(
                "p1", "Wa All in One", config, null, 0, 1, 0, null)));
        AiRunDto launched = mock(AiRunDto.class);
        when(launched.id()).thenReturn("ar-auto");
        when(runs.launchGoal(eq("p1"), any(), eq("system"), eq("https://wao.example"), eq("aa1")))
                .thenReturn(launched);

        service.checkAndLaunch();

        verify(accounts).refreshQuotaSnapshots();
        ArgumentCaptor<String> goal = ArgumentCaptor.forClass(String.class);
        verify(runs).launchGoal(eq("p1"), goal.capture(), eq("system"), eq("https://wao.example"), eq("aa1"));
        assertThat(goal.getValue()).contains("真实用户链路").contains("不要制造演示数据");
    }

    @Test
    void recentAiActivityPreventsQuotaRefreshAndAutomaticLaunch() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.latestActivityAt()).thenReturn(LocalDateTime.now().minusHours(2));

        service.checkAndLaunch();

        verify(accounts, org.mockito.Mockito.never()).refreshQuotaSnapshots();
        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
    }

    @Test
    void secondNodeThatMissesAtomicClaimDoesNothing() {
        when(stateMapper.update(isNull(), any())).thenReturn(0);

        service.checkAndLaunch();

        verify(runMapper, org.mockito.Mockito.never()).selectCount(any());
        verify(accounts, org.mockito.Mockito.never()).refreshQuotaSnapshots();
        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
    }
}
