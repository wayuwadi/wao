package cn.wayuwa.wao.service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountIdentityMapper;
import cn.wayuwa.wao.mapper.AccountMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeishuTaskNotifierTest {

    private final FeishuWebhookNotifier webhook = mock(FeishuWebhookNotifier.class);
    private final SettingService settings = mock(SettingService.class);
    private final ProjectNotifyGate gate = mock(ProjectNotifyGate.class);
    private final FeishuTaskNotifier notifier = new FeishuTaskNotifier(webhook, settings,
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
        assertThat(notifier.notifyRunQueued("t1", "p2", "标题", "家庭资料归档", "Codex", null, null)).isFalse();
        assertThat(notifier.notifyRunStarted("t1", "p2", "标题", "家庭资料归档", "Codex", "娃哥电脑")).isFalse();
        assertThat(notifier.notifyRunFinished("t1", "p2", "标题", "家庭资料归档", "Codex", null, true, false, "跑完了"))
                .isFalse();

        verify(webhook, never()).notifyCard(any());
    }

    @Test
    void stillNotifiesForProjectsThatKeepTheSwitchOn() {
        when(gate.enabled("p1")).thenReturn(true);
        when(settings.get(SettingService.QUICK_COLLECT_MENTIONS, "")).thenReturn("");
        when(webhook.notifyCard(any())).thenReturn(true);

        assertThat(notifier.notifyTaskCreated("t1", "p1", "标题", "Wa All in One", "娃哥", "描述")).isTrue();
        assertThat(notifier.notifyRunFinished("t1", "p1", "标题", "Wa All in One", "Codex", null, true, false, "跑完了"))
                .isTrue();

        verify(webhook, org.mockito.Mockito.times(2)).notifyCard(any());
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
