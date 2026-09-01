package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.UnauthorizedException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.AccountResourceEntity;
import cn.wayuwa.wao.domain.VerificationMessageEntity;
import cn.wayuwa.wao.dto.InboundVerificationMessageRequest;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.mapper.AccountResourceMapper;
import cn.wayuwa.wao.mapper.VerificationMessageMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AccountResourceServiceTest {
    private final AccountResourceMapper accounts = mock(AccountResourceMapper.class);
    private final VerificationMessageMapper messages = mock(VerificationMessageMapper.class);
    private final VaultCryptoService crypto = mock(VaultCryptoService.class);
    private final SettingService settings = mock(SettingService.class);
    private final FeishuWebhookConfigService webhooks = mock(FeishuWebhookConfigService.class);
    private final FeishuWebhookNotifier notifier = mock(FeishuWebhookNotifier.class);
    private final Map<String, String> storedSettings = new HashMap<>();
    private AccountResourceService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(settings.get(anyString())).thenAnswer(call -> storedSettings.get(call.getArgument(0)));
        org.mockito.Mockito.doAnswer(call -> {
            storedSettings.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(settings).put(anyString(), anyString());
        when(crypto.encrypt(anyString())).thenAnswer(call -> {
            byte[] plaintext = call.<String>getArgument(0).getBytes(StandardCharsets.UTF_8);
            return new VaultCryptoService.Encrypted(plaintext, new byte[] {1});
        });
        when(crypto.decrypt(any(byte[].class), any(byte[].class))).thenAnswer(call ->
                new String(call.getArgument(0), StandardCharsets.UTF_8));
        when(accounts.selectList(any(Wrapper.class))).thenReturn(List.of(account("小红书运营号")));
        service = new AccountResourceService(accounts, messages, crypto, settings, webhooks, notifier);
    }

    @Test
    void extractsCodesOnlyWhenMessageContainsVerificationContext() {
        assertThat(AccountResourceService.extractCode("【小红书】您的验证码是：621059，5分钟内有效"))
                .isEqualTo("621059");
        assertThat(AccountResourceService.extractCode("850748 为您的登录动态码，请勿泄漏"))
                .isEqualTo("850748");
        assertThat(AccountResourceService.extractCode("订单 106941661388530 将于 2026-09-01 送达"))
                .isNull();
    }

    @Test
    void normalizesMainlandCountryCodeBeforeMatchingAccounts() {
        assertThat(AccountResourceService.canonicalPhone("+86 199-4578-3940"))
                .isEqualTo("19945783940");
        assertThat(AccountResourceService.canonicalPhone("19945783940"))
                .isEqualTo("19945783940");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestsEncryptedMessageAndForwardsMatchedCodeExactlyOnce() {
        String ingestToken = service.rotateToken("ingest").token();
        when(messages.selectOne(any(Wrapper.class))).thenReturn(null);
        when(notifier.enabled()).thenReturn(true);
        when(notifier.resolvedUrl()).thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/test");
        ArgumentCaptor<VerificationMessageEntity> inserted = ArgumentCaptor.forClass(VerificationMessageEntity.class);

        var result = service.ingest("Bearer " + ingestToken, new InboundVerificationMessageRequest(
                "+86 19945783940", "小红书", "主板机-1-103",
                "【小红书】您的验证码是：621059，5分钟内有效", "sms-1",
                LocalDateTime.now().minusSeconds(1)));

        verify(messages).insert(inserted.capture());
        verify(notifier).send(anyString(), any(FeishuWebhookNotifier.Card.class));
        assertThat(new String(inserted.getValue().getCodeCiphertext(), StandardCharsets.UTF_8))
                .isEqualTo("621059");
        assertThat(inserted.getValue().getPhoneNumber()).isEqualTo("19945783940");
        assertThat(result.codeRecognized()).isTrue();
        assertThat(result.forwarded()).isTrue();
        assertThat(result.duplicate()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateInboundMessageDoesNotSendAnotherFeishuCard() {
        String ingestToken = service.rotateToken("ingest").token();
        VerificationMessageEntity existing = message("SENT");
        when(messages.selectOne(any(Wrapper.class))).thenReturn(existing);

        var result = service.ingest("Bearer " + ingestToken, new InboundVerificationMessageRequest(
                "19945783940", "小红书", "主板机", "验证码：621059", "sms-1", LocalDateTime.now()));

        assertThat(result.duplicate()).isTrue();
        verify(notifier, never()).send(anyString(), any(FeishuWebhookNotifier.Card.class));
        verify(messages, never()).insert(any(VerificationMessageEntity.class));
    }

    @Test
    void ingestAndCrawlerTokensCannotReplaceEachOther() {
        String ingestToken = service.rotateToken("ingest").token();
        String crawlerToken = service.rotateToken("crawler").token();

        assertThat(ingestToken).isNotEqualTo(crawlerToken);
        assertThatThrownBy(() -> service.ingest("Bearer " + crawlerToken,
                new InboundVerificationMessageRequest("19945783940", "", "", "验证码：621059",
                        "sms-2", LocalDateTime.now())))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("令牌无效");
    }

    @Test
    @SuppressWarnings("unchecked")
    void crawlerReadsOnlyRecentCodeForNormalizedPhone() {
        String crawlerToken = service.rotateToken("crawler").token();
        when(messages.selectOne(any(Wrapper.class))).thenReturn(message("SENT"));

        var result = service.latestCode("Bearer " + crawlerToken, "+86 19945783940", 300);

        assertThat(result.phoneNumber()).isEqualTo("19945783940");
        assertThat(result.code()).isEqualTo("621059");
        assertThat(result.accountLabels()).containsExactly("小红书运营号");
    }

    private AccountResourceEntity account(String label) {
        AccountResourceEntity entity = new AccountResourceEntity();
        entity.setId("ar1");
        entity.setAccountLabel(label);
        entity.setPhoneNumber("19945783940");
        entity.setEnabled(true);
        return entity;
    }

    private VerificationMessageEntity message(String status) {
        VerificationMessageEntity entity = new VerificationMessageEntity();
        entity.setId("vm1");
        entity.setCodeCiphertext("621059".getBytes(StandardCharsets.UTF_8));
        entity.setCodeNonce(new byte[] {1});
        entity.setMessageCiphertext("验证码：621059".getBytes(StandardCharsets.UTF_8));
        entity.setMessageNonce(new byte[] {1});
        entity.setForwardStatus(status);
        entity.setPhoneNumber("19945783940");
        entity.setReceivedAt(LocalDateTime.now());
        return entity;
    }
}
