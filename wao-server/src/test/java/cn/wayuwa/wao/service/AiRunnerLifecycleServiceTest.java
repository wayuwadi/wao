package cn.wayuwa.wao.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import cn.wayuwa.wao.domain.AiRunnerNodeEntity;
import cn.wayuwa.wao.dto.AiRunnerDto;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AiRunnerNodeMapper;
import org.junit.jupiter.api.Test;

class AiRunnerLifecycleServiceTest {

    private final AiRunnerNodeMapper mapper = mock(AiRunnerNodeMapper.class);
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final AiRunnerLifecycleService service = new AiRunnerLifecycleService(mapper, notifier);

    @Test
    void offlineTransitionUpdatesStateWithoutGroupNotification() {
        AiRunnerNodeEntity runner = runner(false);
        runner.setOnlineState(true);
        runner.setLastSeenAt(LocalDateTime.now().minusMinutes(3));
        when(mapper.selectList(any())).thenReturn(List.of(runner));
        when(mapper.update(any(), any())).thenReturn(1);

        int changed = service.detectOffline(LocalDateTime.now());

        assertEquals(1, changed);
        verify(mapper).update(any(), any());
        verify(notifier, never()).notifyCard(any());
    }

    @Test
    void onlineHeartbeatUpdatesStateWithoutGroupNotification() {
        AiRunnerNodeEntity runner = runner(false);
        when(mapper.selectById("rn1")).thenReturn(runner);
        when(mapper.update(any(), any())).thenReturn(1);

        service.markOnline("rn1");

        verify(mapper).update(any(), any());
        verify(notifier, never()).notifyCard(any());
    }

    @Test
    void stableOnlineHeartbeatDoesNotNotify() {
        AiRunnerNodeEntity runner = runner(true);
        when(mapper.selectById("rn1")).thenReturn(runner);

        service.markOnline("rn1");

        verify(notifier, never()).notifyCard(any());
    }

    @Test
    void manualRemovalStillNotifies() {
        AiRunnerDto runner = new AiRunnerDto("rn1", "娃哥 Windows 电脑", "windows", List.of("codex"),
                1, null, 0, false, null, null, null, 9, false, null, true, false);

        service.notifyRemoved(runner);

        verify(notifier).notifyCard(any());
    }

    private AiRunnerNodeEntity runner(boolean online) {
        AiRunnerNodeEntity runner = new AiRunnerNodeEntity();
        runner.setId("rn1");
        runner.setName("娃哥 Windows 电脑");
        runner.setEngines("codex,claude");
        runner.setOnlineState(online);
        return runner;
    }
}
