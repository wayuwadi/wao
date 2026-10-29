package cn.wayuwa.wao.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.CommentDto;
import cn.wayuwa.wao.dto.NotificationSettingsDto.SystemPolicy;
import cn.wayuwa.wao.dto.TaskDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class FeishuCommentNotifierTest {
    @Test
    void privateInboxCommentsAndMentionsNeverReachTheWebhookUnderEnabledPolicies() {
        FeishuWebhookNotifier webhook = mock(FeishuWebhookNotifier.class);
        ProjectNotifyGate gate = mock(ProjectNotifyGate.class);
        NotificationSettingsService settings = mock(NotificationSettingsService.class);
        FeishuCommentNotifier notifier = new FeishuCommentNotifier(webhook, gate, settings);
        when(gate.enabled(any())).thenReturn(true);
        when(settings.allows(any())).thenReturn(true);
        TaskDto task = mock(TaskDto.class);
        when(task.id()).thenReturn("private"); when(task.title()).thenReturn("私人任务");
        var comment = new CommentDto("c1", "member", "2026-10-29T12:00:00+08:00", "私人评论");
        for (String project : java.util.Arrays.asList(null, "", "   ")) {
            when(task.project()).thenReturn(project);
            notifier.notifyComment(task, comment, "本人", "收集箱", List.of());
            notifier.notifyComment(task, comment, "本人", "收集箱", List.of("ou_other"));
        }
        org.mockito.Mockito.verifyNoInteractions(webhook);
        when(task.project()).thenReturn("p1");
        notifier.notifyComment(task, comment, "本人", "共享清单", List.of());
        notifier.notifyComment(task, comment, "本人", "共享清单", List.of("ou_other"));
        verify(webhook, times(2)).notifyCard(any());
    }


    @Test
    void explicitMentionUsesManualPolicyWhileAnOrdinaryCommentStaysSilent() {
        FeishuWebhookNotifier webhook = mock(FeishuWebhookNotifier.class);
        ProjectNotifyGate gate = mock(ProjectNotifyGate.class);
        NotificationSettingsService settings = mock(NotificationSettingsService.class);
        FeishuCommentNotifier notifier = new FeishuCommentNotifier(webhook, gate, settings);
        TaskDto task = mock(TaskDto.class);
        when(task.id()).thenReturn("t1");
        when(task.project()).thenReturn("p1");
        when(task.title()).thenReturn("通知策略");
        when(gate.enabled("p1")).thenReturn(true);
        when(settings.allows(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Predicate<SystemPolicy> scene = invocation.getArgument(0);
            return scene.test(new SystemPolicy(true, false, "22:00", "08:00",
                    false, false, false, true, true, true, true, true));
        });
        CommentDto comment = new CommentDto("c1", "wd", "2026-08-28T18:00:00+08:00", "请查看");

        notifier.notifyComment(task, comment, "娃弟", "WAO", List.of());
        notifier.notifyComment(task, comment, "娃弟", "WAO", List.of("ou_wg"));

        verify(webhook, times(1)).notifyCard(any());
    }
}
