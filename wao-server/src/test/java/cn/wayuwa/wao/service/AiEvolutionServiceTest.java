package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import cn.wayuwa.wao.dto.AiEvolutionSuggestionDto;
import cn.wayuwa.wao.dto.AiProjectConfigDto;
import cn.wayuwa.wao.dto.AiRunDto;
import cn.wayuwa.wao.dto.AiWeeklyQuotaDto;
import cn.wayuwa.wao.dto.AiWorkflowDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AiEvolutionStateMapper;
import cn.wayuwa.wao.mapper.AiTaskRunMapper;
import cn.wayuwa.wao.service.AiAssistantAssetService.TaskMiningContext;
import cn.wayuwa.wao.service.AiAssistantAssetService.TaskMiningSource;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final AiEvolutionSuggestionRepository suggestions = mock(AiEvolutionSuggestionRepository.class);
    private final AiAssistantService assistant = mock(AiAssistantService.class);
    private final AiAssistantAssetService assets = mock(AiAssistantAssetService.class);
    private final AiEvolutionService service = new AiEvolutionService(
            accounts, runs, runMapper, stateMapper, notifier, suggestions, assistant, assets,
            new ObjectMapper(), "https://wao.example");

    @Test
    void overviewDoesNotTreatUnknownQuotaAsFullAllowance() {
        when(accounts.weeklyQuotas()).thenReturn(List.of());

        AiEvolutionOverviewDto overview = service.overview();

        assertThat(overview.weeklyQuotas()).isEmpty();
        assertThat(overview.autoStatus()).isEqualTo("quota_unavailable");
        assertThat(overview.autoMessage()).contains("尚未获取").contains("暂停");
        assertThat(overview.suggestions()).isEmpty();
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
        when(suggestions.latest()).thenReturn(List.of(suggestion("es1", "修复真实体验问题")));
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
    void refreshMinesExactlyFiveTraceableSuggestionsFromRealAssets() {
        TaskMiningSource source = new TaskMiningSource(
                1, "chat", "飞书群聊 · 2026-08-12", "/kb/k1?doc=d1");
        when(assets.taskMiningContext()).thenReturn(new TaskMiningContext(
                "<wao_task_mining_sources>[source index=\"1\"]真实需求[/source]</wao_task_mining_sources>",
                List.of(source), List.of("已有任务")));
        when(assistant.runReadOnlyJob(eq("evolution-suggestions"), any())).thenReturn("""
                {"suggestions":[
                  {"title":"候选一","description":"结果一","goal":"目标一","sourceIndex":1},
                  {"title":"候选二","description":"结果二","goal":"目标二","sourceIndex":1},
                  {"title":"候选三","description":"结果三","goal":"目标三","sourceIndex":1},
                  {"title":"候选四","description":"结果四","goal":"目标四","sourceIndex":1},
                  {"title":"候选五","description":"结果五","goal":"目标五","sourceIndex":1}
                ]}
                """);

        List<AiEvolutionSuggestionDto> refreshed = service.refreshSuggestions();

        assertThat(refreshed).hasSize(5).allSatisfy(item -> {
            assertThat(item.sourceTitle()).isEqualTo("飞书群聊 · 2026-08-12");
            assertThat(item.sourceUrl()).isEqualTo("/kb/k1?doc=d1");
            assertThat(item.goal()).contains("真实资产").contains("不要制造演示数据");
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiEvolutionSuggestionDto>> saved = ArgumentCaptor.forClass(List.class);
        verify(suggestions).saveBatch(any(), saved.capture());
        assertThat(saved.getValue()).containsExactlyElementsOf(refreshed);
    }

    @Test
    void refreshKeepsPreviousBatchWhenRealEvidenceIsInsufficient() {
        TaskMiningSource source = new TaskMiningSource(1, "knowledge", "产品想法", "/kb/k1?doc=d1");
        when(assets.taskMiningContext()).thenReturn(new TaskMiningContext(
                "<wao_task_mining_sources>[source index=\"1\"]只有一条想法[/source]</wao_task_mining_sources>",
                List.of(source), List.of()));
        when(suggestions.latest()).thenReturn(List.of(suggestion("old-1", "上一批任务")));
        when(assistant.runReadOnlyJob(eq("evolution-suggestions"), any()))
                .thenReturn("{\"error\":\"只有一条可靠线索\",\"suggestions\":[]}");

        assertThatThrownBy(service::refreshSuggestions)
                .hasMessageContaining("真实资料暂不足").hasMessageContaining("只有一条可靠线索");
        verify(suggestions, org.mockito.Mockito.never()).saveBatch(any(), any());
    }

    @Test
    void exactlyHalfQuotaKeepsAutomaticEvolutionPaused() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(accounts.weeklyQuotas()).thenReturn(List.of(new AiWeeklyQuotaDto(
                "aa1", "codex", "Codex", "plus", 50, 50, "2026-08-17T10:00:00",
                LocalDateTime.now().toString(), "SYNCED", "七天额度已用 50%")));

        service.checkAndLaunch();
        AiEvolutionOverviewDto overview = service.overview();

        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
        assertThat(overview.autoStatus()).isEqualTo("quota_unavailable");
        assertThat(overview.autoMessage()).contains("未高于 50%");
    }

    @Test
    void recentAiActivityPreventsQuotaRefreshAndAutomaticLaunch() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.latestActivityAt()).thenReturn(LocalDateTime.now().minusHours(2));

        service.checkAndLaunch();

        verify(accounts).refreshQuotaSnapshots();
        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
    }

    @Test
    void lowWeeklyQuotaSendsOneFeishuReminderPerResetWindowEvenDuringCooldown() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(runMapper.latestActivityAt()).thenReturn(LocalDateTime.now().minusHours(2));
        when(accounts.weeklyQuotas()).thenReturn(List.of(new AiWeeklyQuotaDto(
                "aa1", "codex", "Codex", "plus", 58, 42, "2026-08-17T10:00:00",
                LocalDateTime.now().toString(), "SYNCED", "七天额度已用 58%")));
        when(notifier.notifyCard(any())).thenReturn(true);

        service.checkAndLaunch();

        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);
        verify(notifier).notifyCard(card.capture());
        assertThat(card.getValue().title()).contains("低于 50%");
        assertThat(card.getValue().body()).contains("周剩余 42%").contains("2026-08-17T10:00:00");
        verify(stateMapper, org.mockito.Mockito.times(3)).update(isNull(), any());
        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
    }

    @Test
    void alreadyRemindedResetWindowDoesNotSendDuplicateLowQuotaCard() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(accounts.weeklyQuotas()).thenReturn(List.of(new AiWeeklyQuotaDto(
                "aa1", "codex", "Codex", "plus", 58, 42, "2026-08-17T10:00:00",
                LocalDateTime.now().toString(), "SYNCED", "七天额度已用 58%")));
        AiEvolutionStateEntity state = new AiEvolutionStateEntity();
        state.setLastLowQuotaKey("aa1@2026-08-17T10:00:00");
        when(stateMapper.selectById("autonomous-evolution")).thenReturn(state);

        service.checkAndLaunch();

        verify(notifier, org.mockito.Mockito.never()).notifyCard(any());
        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
    }

    @Test
    void recoveredAccountDoesNotCauseAnotherAccountToRepeatItsReminder() {
        when(stateMapper.update(isNull(), any())).thenReturn(1);
        when(runMapper.selectCount(any())).thenReturn(0L);
        when(accounts.weeklyQuotas()).thenReturn(List.of(
                new AiWeeklyQuotaDto("aa1", "codex", "Codex A", "plus", 30, 70,
                        "2026-08-17T10:00:00", LocalDateTime.now().toString(), "SYNCED", null),
                new AiWeeklyQuotaDto("aa2", "codex", "Codex B", "plus", 58, 42,
                        "2026-08-17T10:00:00", LocalDateTime.now().toString(), "SYNCED", null)));
        AiEvolutionStateEntity state = new AiEvolutionStateEntity();
        state.setLastLowQuotaKey("aa1@2026-08-17T10:00:00|aa2@2026-08-17T10:00:00");
        when(stateMapper.selectById("autonomous-evolution")).thenReturn(state);

        service.checkAndLaunch();

        verify(notifier, org.mockito.Mockito.never()).notifyCard(any());
    }

    @Test
    void secondNodeThatMissesAtomicClaimDoesNothing() {
        when(stateMapper.update(isNull(), any())).thenReturn(0);

        service.checkAndLaunch();

        verify(runMapper, org.mockito.Mockito.never()).selectCount(any());
        verify(accounts, org.mockito.Mockito.never()).refreshQuotaSnapshots();
        verify(runs, org.mockito.Mockito.never()).launchGoal(any(), any(), any(), any(), any());
    }

    private static AiEvolutionSuggestionDto suggestion(String id, String title) {
        return new AiEvolutionSuggestionDto(id, title, "从真实链路完成修复", "ri-magic-line",
                "请检查真实用户链路并修复，不要制造演示数据", "飞书群聊 · 2026-08-12", "/kb/k1?doc=d1");
    }
}
