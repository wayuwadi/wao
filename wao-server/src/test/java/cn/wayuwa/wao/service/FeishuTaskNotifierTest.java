package cn.wayuwa.wao.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.auth.FeishuLoginProperties;
import cn.wayuwa.wao.feishu.FeishuAppLinks;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuTaskNotifierTest {

    private final FeishuWebhookNotifier webhook = mock(FeishuWebhookNotifier.class);
    private final SettingService settings = mock(SettingService.class);
    private final ProjectNotifyGate gate = mock(ProjectNotifyGate.class);
    private final FeishuAppLinks links = new FeishuAppLinks(
            new FeishuLoginProperties(false, "", "", "", "", "https://open.feishu.cn"));
    private final FeishuTaskNotifier notifier = new FeishuTaskNotifier(webhook, links, settings,
            mock(AccountMapper.class), mock(AccountIdentityMapper.class), gate);

    @Test
    void skipsEveryProjectScopedCardWhenTheProjectIsMuted() {
        when(gate.enabled("p2")).thenReturn(false);

        assertThat(notifier.notifyTaskCreated("t1", "p2", "标题", "家庭资料归档", "娃哥", "描述")).isFalse();
        assertThat(notifier.notifyStatusChanged("t1", "p2", "标题", "家庭资料归档", "待办", "进行中", "娃哥")).isFalse();
        assertThat(notifier.notifySubtasksAdded("t1", "p2", "标题", "家庭资料归档", List.of("子任务"),
                "娃哥", "娃弟", List.of("ou_wd"))).isFalse();
        assertThat(notifier.notifyCollecting("t1", "p2", "标题", "家庭资料归档", "描述", null)).isFalse();
        assertThat(notifier.notifyProjectCreated("p2", "家庭资料归档", "娃哥", "目标")).isFalse();
        assertThat(notifier.notifyProjectStarted("p2", "家庭资料归档", "娃哥", "目标")).isFalse();
        assertThat(notifier.notifyIdleAutoRun("t1", "p2", "标题", "家庭资料归档", 2, "fw-auto")).isFalse();
        assertThat(notifier.notifyHttpPerformanceInspectionStarted(
                "t1", "p2", "标题", "家庭资料归档", 24, 5, 2, "fw-auto")).isFalse();
        assertThat(notifier.notifyRunFailed("t1", "p2", "标题", null, false, "跑失败了"))
                .isFalse();

        verify(webhook, never()).notifyCard(any());
        verify(webhook, never()).notifyCard(eq("fw-auto"), any());
    }

    @Test
    void stillNotifiesForProjectsThatKeepTheSwitchOn() {
        when(gate.enabled("p1")).thenReturn(true);
        when(settings.get(SettingService.QUICK_COLLECT_MENTIONS, "")).thenReturn("");
        when(webhook.notifyCard(any())).thenReturn(true);
        when(webhook.notifyComicCard(any())).thenReturn(true);

        assertThat(notifier.notifyTaskCreated("t1", "p1", "标题", "Wa All in One", "娃哥", "描述")).isTrue();
        assertThat(notifier.notifyRunFailed("t1", "p1", "标题", null, false, "跑失败了"))
                .isTrue();
        assertThat(notifier.notifyInsightReport("p1", "Wa All in One", 7, 2, 1, List.of("家庭记账", "AI 助手")))
                .isTrue();

        verify(webhook, org.mockito.Mockito.times(2)).notifyCard(any());
        verify(webhook).notifyComicCard(any());
    }

    @Test
    void unreportedAiRunCardOnlyAsksForResultConfirmation() {
        when(gate.enabled("p1")).thenReturn(true);
        when(webhook.notifyCard(any())).thenReturn(true);
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);

        assertThat(notifier.notifyRunFailed(
                "t1", "p1", "通知优化", "合并 2 个任务", true, "未报告结果")).isTrue();

        verify(webhook).notifyCard(card.capture());
        assertThat(card.getValue().title()).isEqualTo("AI 已结束，但没说清成没成");
        assertThat(card.getValue().fields()).containsExactly(List.of("任务", "通知优化 · 合并 2 个任务"));
        assertThat(card.getValue().body()).contains("**需要确认**", "未报告结果");
    }

    @Test
    void failedAiRunCardKeepsTheReasonShortAndDropsDecorativeFields() {
        when(gate.enabled("p1")).thenReturn(true);
        when(webhook.notifyCard(any())).thenReturn(true);
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);

        notifier.notifyRunFailed("t1", "p1", "通知优化", "整个任务", false,
                "部署失败：Linux 主节点健康检查未通过。".repeat(20));

        verify(webhook).notifyCard(card.capture());
        assertThat(card.getValue().title()).isEqualTo("AI 执行未完成");
        assertThat(card.getValue().fields()).containsExactly(List.of("任务", "通知优化"));
        assertThat(card.getValue().body()).startsWith("**失败原因**\n").hasSizeLessThanOrEqualTo(251);
    }

    /** 群聊洞察报告使用漫画卡片，字段里带项目名，按钮落到项目洞察页。 */
    @Test
    void insightReportUsesComicCardWithProjectAndLink() {
        when(gate.enabled("p1")).thenReturn(true);
        when(webhook.notifyComicCard(any())).thenReturn(true);
        ArgumentCaptor<FeishuWebhookNotifier.ComicCard> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.ComicCard.class);

        notifier.notifyInsightReport("p1", "Wa All in One", 7, 2, 1, List.of("家庭记账", "AI 助手"));

        verify(webhook).notifyComicCard(card.capture());
        assertThat(card.getValue().title()).isEqualTo("AI 洞察报告");
        assertThat(card.getValue().headline()).contains("Wa All in One");
        assertThat(card.getValue().imageUrl()).contains("/comic/insight.svg");
        assertThat(card.getValue().linkText()).isEqualTo("查看洞察详情");
        assertThat(card.getValue().linkUrl()).contains("/projects/p1");
    }

    @Test
    void idleAutoRunNamesTheTriggerAndUsesTheConfiguredWebhook() {
        when(gate.enabled("p1")).thenReturn(true);
        when(webhook.notifyCard(eq("fw-auto"), any())).thenReturn(true);
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);

        assertThat(notifier.notifyIdleAutoRun(
                "t1", "p1", "修复自动任务", "Wa All in One", 4, "fw-auto")).isTrue();

        verify(webhook).notifyCard(eq("fw-auto"), card.capture());
        assertThat(card.getValue().title()).isEqualTo("闲置时间已自动推进任务");
        assertThat(card.getValue().fields()).contains(
                List.of("项目", "Wa All in One"),
                List.of("触发方式", "连续空闲 4 小时"),
                List.of("执行方式", "AI Run · Plan 模式"));
        assertThat(card.getValue().body()).contains("不是人工点击派工");
        assertThat(card.getValue().linkUrl()).contains("/projects/p1?task=t1");
    }

    @Test
    void httpInspectionNamesTheMeasuredScopeAndUsesTheConfiguredWebhook() {
        when(gate.enabled("p1")).thenReturn(true);
        when(webhook.notifyCard(eq("fw-performance"), any())).thenReturn(true);
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);

        assertThat(notifier.notifyHttpPerformanceInspectionStarted(
                "t1", "p1", "HTTP 性能巡检", "Wa All in One", 48, 8, 3, "fw-performance")).isTrue();

        verify(webhook).notifyCard(eq("fw-performance"), card.capture());
        assertThat(card.getValue().title()).isEqualTo("HTTP 性能 AI 巡检已启动");
        assertThat(card.getValue().fields()).contains(
                List.of("指标窗口", "最近 48 小时"),
                List.of("巡检范围", "Top 8，最多治理 3 个接口"));
        assertThat(card.getValue().body()).contains("真实聚合指标");
    }

    /** 娃哥往娃弟建的任务里加子任务：卡片得说清是娃哥加的，并且 @ 到娃弟。 */
    @Test
    void subtaskCardNamesWhoAddedThemAndMentionsTheStakeholders() {
        when(gate.enabled("p1")).thenReturn(true);
        when(webhook.notifyCard(any())).thenReturn(true);
        ArgumentCaptor<FeishuWebhookNotifier.Card> card = ArgumentCaptor.forClass(FeishuWebhookNotifier.Card.class);

        notifier.notifySubtasksAdded("t1", "p1", "整理证件盒", "Wa All in One",
                List.of("扫描身份证", "归档到 NAS"), "娃哥", "娃弟", List.of("ou_wd"));

        verify(webhook).notifyCard(card.capture());
        assertThat(card.getValue().title()).isEqualTo("娃哥新增了 2 条子任务");
        assertThat(card.getValue().fields()).contains(List.of("添加人", "娃哥"), List.of("任务创建人", "娃弟"));
        assertThat(card.getValue().body()).contains("<at id=ou_wd></at>").contains("扫描身份证");
    }
}
