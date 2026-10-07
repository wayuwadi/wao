package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.FeishuBotInboxEntity;
import cn.wayuwa.wao.mapper.FeishuBotInboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class FeishuBotInboxServiceTest {
    private final FeishuBotInboxMapper mapper = mock(FeishuBotInboxMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuWebhookConfigService bots = mock(FeishuWebhookConfigService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final VaultCryptoService crypto = new VaultCryptoService("ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=");
    private final FeishuBotInboxService service = new FeishuBotInboxService(mapper, crypto, json, bots, settings);

    @Test
    void depositEncryptsEventAndDuplicateDeliveryRetainsSameDurableIdentity() throws Exception {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
        when(bots.captureRoute(any())).thenReturn(new FeishuWebhookConfigService.CaptureRoute("fw1", "NOTE"));
        var event = json.readTree("""
                {"sender":{"sender_type":"user","sender_id":{"open_id":"ou_sender"}},
                "message":{"message_id":"om_1","chat_id":"oc_1","mentions":[{"name":"小记创作"}],
                "content":"private capture content"}}
                """);
        when(mapper.insert(any(FeishuBotInboxEntity.class))).thenReturn(1).thenThrow(new DuplicateKeyException("duplicate"));
        service.enqueue(event, "ev1", "cli_app"); service.enqueue(event, "ev1", "cli_app");
        var stored = ArgumentCaptor.forClass(FeishuBotInboxEntity.class);
        verify(mapper, times(2)).insert(stored.capture());
        assertThat(stored.getAllValues().get(0).getId()).isEqualTo(stored.getAllValues().get(1).getId());
        assertThat(new String(stored.getValue().getPayloadCiphertext(), java.nio.charset.StandardCharsets.UTF_8))
                .doesNotContain("private capture content");
        assertThat(service.event(stored.getValue())).isEqualTo(event);
        assertThat(stored.getValue().getStatus()).isEqualTo("PENDING");
        verify(mapper, never()).complete(any(), any());
    }

    @Test
    void failedDepositsPropagateInsteadOfAcknowledgingLoss() throws Exception {
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
        when(bots.captureRoute(any())).thenReturn(new FeishuWebhookConfigService.CaptureRoute("fw1", "TASK"));
        when(mapper.insert(any(FeishuBotInboxEntity.class))).thenThrow(new IllegalStateException("unavailable"));
        var event = json.readTree("{\"sender\":{\"sender_type\":\"user\"},\"message\":{\"chat_id\":\"oc_1\",\"message_id\":\"om_1\"}}");
        assertThatThrownBy(() -> service.enqueue(event, "ev1", "cli_app")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void workerReplaysRetainedEventAndDoesNotProcessAnotherWorkersClaim() {
        var collect = mock(FeishuQuickCollectService.class);
        var worker = new FeishuBotInboxWorker(mapper, collect);
        var retained = new FeishuBotInboxEntity(); retained.setId("retained");
        when(mapper.pending()).thenReturn(List.of(retained));
        when(mapper.claim(eq("retained"), any())).thenReturn(1, 0, 1);
        doThrow(new IllegalStateException("temporary")).doNothing().when(collect).processInbox(retained);
        worker.drain(); worker.drain(); worker.drain();
        verify(collect, times(2)).processInbox(retained);
        verify(mapper, times(1)).retry(eq("retained"), any());
    }

    @Test
    void oldWorkerCannotCompleteOrProcessReclaimedInbox() {
        var row = new FeishuBotInboxEntity(); row.setId("reclaimed"); row.setLeaseToken("old-lease");
        when(mapper.lockLease("reclaimed")).thenReturn("new-lease");
        assertThatThrownBy(() -> service.requireLease(row)).isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
        when(mapper.complete("reclaimed", "old-lease")).thenReturn(0);
        assertThatThrownBy(() -> service.complete(row)).isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
    }
}
