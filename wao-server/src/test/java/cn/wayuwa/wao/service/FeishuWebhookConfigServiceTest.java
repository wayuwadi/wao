package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.common.VaultCryptoService;
import cn.wayuwa.wao.domain.FeishuWebhookConfigEntity;
import cn.wayuwa.wao.dto.SaveFeishuWebhookEndpointRequest;
import cn.wayuwa.wao.mapper.FeishuWebhookConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;

class FeishuWebhookConfigServiceTest {

    private static final String KEY = "ZKp9ywcbuZOrQCrxx2kd+0N9VJfnzRNSQQZXMP/vXVw=";
    private static final String URL = "https://open.feishu.cn/open-apis/bot/v2/hook/secret-token-1234";

    private final FeishuWebhookConfigMapper mapper = mock(FeishuWebhookConfigMapper.class);
    private final SettingService settings = mock(SettingService.class);
    private final VaultCryptoService crypto = new VaultCryptoService(KEY);
    private final FeishuWebhookConfigService service = new FeishuWebhookConfigService(mapper, crypto, settings, "");

    @BeforeEach
    void mutationsSucceed() {
        when(mapper.update(any(), any())).thenReturn(1);
        when(mapper.delete(any())).thenReturn(1);
    }

    @Test
    void staleRevisionAndConcurrentDatabaseChangeAreRejected() {
        var bot = entity("fw1", true, false, URL); bot.setRevision(3L);
        when(mapper.selectById("fw1")).thenReturn(bot);
        assertThatThrownBy(() -> service.update("fw1", new SaveFeishuWebhookEndpointRequest(
                "新名称", "", "", true, false, null, null, null, null, null, 2L, false)))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
        when(mapper.update(any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.update("fw1", new SaveFeishuWebhookEndpointRequest(
                "新名称", "", "", true, false, null, null, null, null, null, 3L, false)))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
    }

    @Test
    void displayNameCannotBeUsedAsStableMentionIdentity() {
        var bot = entity("fw1", true, false, URL);
        when(mapper.selectById("fw1")).thenReturn(bot);
        assertThatThrownBy(() -> service.update("fw1", new SaveFeishuWebhookEndpointRequest(
                "任务入口", "", "", true, false, "TASK", "任务待办", "任务待办", null, null, 0L, true)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("真实 ID");
        verify(mapper, never()).update(any(), any());
    }

    @Test
    void referencedBotsRequireImpactConfirmationForDisableAndDelete() {
        var bot = entity("fw1", true, true, URL);
        when(mapper.selectById("fw1")).thenReturn(bot);
        var activity = mock(FeishuBotActivityService.class);
        service.setActivityService(activity);
        when(activity.usages(bot)).thenReturn(List.of(new cn.wayuwa.wao.dto.FeishuBotActivityDto.Usage("SYSTEM_DEFAULT", "默认", 1)));
        assertThatThrownBy(() -> service.update("fw1", request("机器人", "", false, true)))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
        assertThatThrownBy(() -> service.delete("fw1", 0L, false))
                .isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
        verify(mapper, never()).delete(any());
    }

    @Test
    void stableMentionIdWinsOverNameAndDisabledRoutesCannotUseLegacyFallback() throws Exception {
        var bot = entity("fw1", true, false, URL);
        bot.setName("任务待办"); bot.setMentionName("任务待办"); bot.setMentionId("ou_custom");
        bot.setCaptureKind("TASK"); bot.setAppId("cli_chat"); bot.setChatId("oc_1");
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
        when(mapper.selectList(any())).thenReturn(List.of(bot));
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(service.captureRoute(json.readTree("[{\"name\":\"旧名称\",\"id\":{\"open_id\":\"ou_custom\"}}]")))
                .isEqualTo(new FeishuWebhookConfigService.CaptureRoute("fw1", "TASK"));
        assertThat(service.captureRoute(json.readTree("[{\"name\":\"任务待办\",\"id\":{\"open_id\":\"ou_wrong\"}}]"))).isNull();
        bot.setEnabled(false);
        assertThat(service.captureRoute(json.readTree("[{\"name\":\"任务待办\",\"id\":{\"open_id\":\"ou_custom\"}}]"))).isNull();
    }

    @Test
    void legacyKnownNamesWorkUntilAttachedButUnknownOrAmbiguousMentionsNeverRoute() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(service.captureRoute(json.readTree("[{\"name\":\"任务待办\"}]")))
                .isEqualTo(new FeishuWebhookConfigService.CaptureRoute(null, "TASK"));
        assertThat(service.captureRoute(json.readTree("[{\"name\":\"普通成员\"}]"))).isNull();
        assertThat(service.captureRoute(json.readTree("[{\"name\":\"任务待办\"},{\"name\":\"小记创作\"}]"))).isNull();
    }

    @Test
    void deletingConfiguredBotLeavesPersistentLegacySuppressionAndExplicitRecreationCanRoute() throws Exception {
        var bot = entity("fw1", true, false, URL); bot.setName("任务待办"); bot.setCaptureKind("TASK");
        bot.setMentionName("任务待办"); bot.setChatId("oc_1"); bot.setAppId("cli_chat");
        when(mapper.selectById("fw1")).thenReturn(bot);
        service.delete("fw1", 0L, true);
        verify(settings).put("feishu.bot-center.legacy-task-retired", "true");
        when(settings.getBoolean("feishu.bot-center.legacy-task-retired")).thenReturn(true);
        var mentions = new com.fasterxml.jackson.databind.ObjectMapper().readTree("[{\"name\":\"任务待办\"}]");
        assertThat(service.captureRoute(mentions)).isNull();
        when(mapper.selectList(any())).thenReturn(List.of(bot));
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
        assertThat(service.captureRoute(mentions)).isEqualTo(new FeishuWebhookConfigService.CaptureRoute("fw1", "TASK"));
    }

    @Test
    void captureBindingRequiresConfiguredApplicationAndRejectsDuplicateMentions() {
        when(settings.get(SettingService.CHAT_SYNC_APP_ID, "")).thenReturn("cli_chat");
        when(settings.get(SettingService.CHAT_SYNC_CHAT_ID, "")).thenReturn("oc_1");
        var request = new SaveFeishuWebhookEndpointRequest("任务待办", "", URL, true, false,
                "TASK", "", "任务待办", "oc_1", "cli_chat", null, false);
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(BadRequestException.class);
        when(settings.get(SettingService.CHAT_SYNC_APP_SECRET)).thenReturn("secret");
        var other = entity("fw-other", true, false, URL);
        other.setCaptureKind("TASK"); other.setMentionName("任务待办");
        when(mapper.selectList(any())).thenReturn(List.of(other));
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(cn.wayuwa.wao.common.ConflictException.class);
    }

    @Test
    void firstConfigBecomesDefaultAndNeverReturnsOrStoresPlaintext() {
        when(mapper.selectCount(null)).thenReturn(0L);
        ArgumentCaptor<FeishuWebhookConfigEntity> inserted = ArgumentCaptor.forClass(FeishuWebhookConfigEntity.class);

        var result = service.create(request(" WAO 主群 ", URL, true, false));

        verify(mapper).insert(inserted.capture());
        FeishuWebhookConfigEntity entity = inserted.getValue();
        assertThat(entity.getName()).isEqualTo("WAO 主群");
        assertThat(entity.getDefaultMarker()).isEqualTo("SYSTEM");
        assertThat(new String(entity.getWebhookCiphertext(), StandardCharsets.UTF_8)).doesNotContain("secret-token");
        assertThat(crypto.decrypt(entity.getWebhookCiphertext(), entity.getWebhookNonce())).isEqualTo(URL);
        assertThat(result.systemDefault()).isTrue();
        assertThat(result.urlMask()).endsWith("••••1234").doesNotContain("secret-token");
        verify(settings).put(SettingService.FEISHU_WEBHOOK_REGISTRY_INITIALIZED, "true");
        // 默认项双写旧键只用于滚动发布；SettingService 会再次加密，调用参数不得出现在响应或日志。
        verify(settings).put(SettingService.FEISHU_WEBHOOK_URL, URL);
    }

    @Test
    void firstListImportsLegacySingletonOnceAndReturnsOnlyMaskedAddress() {
        AtomicReference<FeishuWebhookConfigEntity> stored = new AtomicReference<>();
        when(mapper.selectCount(null)).thenAnswer(invocation -> stored.get() == null ? 0L : 1L);
        when(settings.get(SettingService.FEISHU_WEBHOOK_URL)).thenReturn(URL);
        when(settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED)).thenReturn(true);
        when(mapper.selectList(any())).thenAnswer(invocation ->
                stored.get() == null ? List.of() : List.of(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).insert(any(FeishuWebhookConfigEntity.class));

        var first = service.list();
        var second = service.list();

        assertThat(first).singleElement().satisfies(config -> {
            assertThat(config.name()).isEqualTo("系统通知（原配置）");
            assertThat(config.systemDefault()).isTrue();
            assertThat(config.urlMask()).endsWith("••••1234").doesNotContain("secret-token");
        });
        assertThat(second).isEqualTo(first);
        assertThat(crypto.decrypt(stored.get().getWebhookCiphertext(), stored.get().getWebhookNonce()))
                .isEqualTo(URL);
        verify(mapper, times(1)).insert(any(FeishuWebhookConfigEntity.class));
    }

    @Test
    void editWithBlankUrlKeepsExistingCiphertext() {
        FeishuWebhookConfigEntity existing = entity("fw1", true, true, URL);
        byte[] ciphertext = existing.getWebhookCiphertext();
        byte[] nonce = existing.getWebhookNonce();
        when(mapper.selectById("fw1")).thenReturn(existing);

        var result = service.update("fw1", request("新名称", "  ", false, true));

        assertThat(existing.getWebhookCiphertext()).isSameAs(ciphertext);
        assertThat(existing.getWebhookNonce()).isSameAs(nonce);
        assertThat(existing.getName()).isEqualTo("新名称");
        assertThat(result.enabled()).isFalse();
        verify(mapper).update(eq(existing), any());
    }

    @Test
    void rejectsNonOfficialOrInsecureWebhookUrls() {
        when(mapper.selectCount(null)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(request(
                "内网地址", "http://127.0.0.1/internal", true, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("飞书官方 HTTPS");
        assertThatThrownBy(() -> service.create(request(
                "伪造域名", "https://open.feishu.cn.attacker.example/open-apis/bot/v2/hook/key", true, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("飞书官方 HTTPS");
    }

    @Test
    void boundsWebhookTokenToSafeAsciiAndCiphertextColumnCapacity() {
        when(mapper.selectCount(null)).thenReturn(0L);
        String maximumUrl = "https://open.feishu.cn/open-apis/bot/v2/hook/" + "a".repeat(512);
        ArgumentCaptor<FeishuWebhookConfigEntity> inserted = ArgumentCaptor.forClass(FeishuWebhookConfigEntity.class);

        service.create(request("最长合法 token", maximumUrl, true, true));

        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getWebhookCiphertext().length).isLessThanOrEqualTo(2048);
        assertThatThrownBy(() -> service.create(request("过长 token",
                "https://open.feishu.cn/open-apis/bot/v2/hook/" + "a".repeat(513), true, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("飞书官方 HTTPS");
        assertThatThrownBy(() -> service.create(request("非 ASCII token",
                "https://open.feishu.cn/open-apis/bot/v2/hook/机器人", true, true)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("飞书官方 HTTPS");
    }

    @Test
    void safelyListsLegacyInvalidAndUnreadableCredentialsAsDisabled() {
        FeishuWebhookConfigEntity unsupported = entity(
                "fw-unsupported", true, true, "https://example.com/open-apis/bot/v2/hook/old-secret");
        FeishuWebhookConfigEntity unreadable = entity("fw-unreadable", true, false, URL);
        unreadable.setWebhookCiphertext(new byte[] {1, 2, 3});
        when(mapper.selectCount(null)).thenReturn(2L);
        when(mapper.selectList(any())).thenReturn(List.of(unsupported, unreadable));

        var result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).urlMask()).contains("需替换");
        assertThat(result.get(0).urlMask()).doesNotContain("example.com").doesNotContain("old-secret");
        assertThat(result.get(0).urlSet()).isFalse();
        assertThat(result.get(0).enabled()).isFalse();
        assertThat(result.get(1).urlMask()).contains("无法读取");
        assertThat(result.get(1).urlSet()).isFalse();
        assertThat(result.get(1).enabled()).isFalse();
    }

    @Test
    void invalidLegacyDefaultCannotBeSentOrTested() {
        FeishuWebhookConfigEntity unsafe = entity(
                "fw-unsafe", true, true, "https://example.com/open-apis/bot/v2/hook/old-secret");
        when(mapper.selectCount(null)).thenReturn(1L);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(unsafe);
        when(mapper.selectById("fw-unsafe")).thenReturn(unsafe);

        var target = service.defaultTarget();

        assertThat(target).isPresent().get().satisfies(value -> {
            assertThat(value.enabled()).isFalse();
            assertThat(value.url()).isEmpty();
        });
        assertThatThrownBy(() -> service.endpointUrl("fw-unsafe"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("无法安全使用")
                .hasMessageNotContaining("example.com")
                .hasMessageNotContaining("old-secret");
    }

    @Test
    void nonOfficialLegacySingletonIsImportedButNeverBecomesASendTarget() {
        String unsafeUrl = "https://example.com/open-apis/bot/v2/hook/legacy-secret";
        AtomicReference<FeishuWebhookConfigEntity> stored = new AtomicReference<>();
        when(mapper.selectCount(null)).thenAnswer(invocation -> stored.get() == null ? 0L : 1L);
        when(settings.get(SettingService.FEISHU_WEBHOOK_URL)).thenReturn(unsafeUrl);
        when(settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED)).thenReturn(true);
        when(mapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(mapper).insert(any(FeishuWebhookConfigEntity.class));

        var target = service.defaultTarget();

        assertThat(target).isPresent().get().satisfies(value -> {
            assertThat(value.enabled()).isFalse();
            assertThat(value.url()).isEmpty();
        });
        assertThat(crypto.decrypt(stored.get().getWebhookCiphertext(), stored.get().getWebhookNonce()))
                .isEqualTo(unsafeUrl);
        verify(settings).put(SettingService.FEISHU_WEBHOOK_REGISTRY_INITIALIZED, "true");
    }

    @Test
    void malformedBase64OrUndecryptableLegacySettingBecomesARepairableDisabledRecord() {
        assertUnreadableLegacySettingIsContained(
                new IllegalArgumentException("illegal base64 legacy-secret-must-not-leak"));
        assertUnreadableLegacySettingIsContained(
                new IllegalStateException("decrypt failed legacy-secret-must-not-leak"));
    }

    @Test
    void invalidLegacyCredentialMustBeReplacedBeforeEditingCanSucceed() {
        FeishuWebhookConfigEntity unsafe = entity(
                "fw-unsafe", true, false, "https://example.com/open-apis/bot/v2/hook/old-secret");
        when(mapper.selectById("fw-unsafe")).thenReturn(unsafe);

        assertThatThrownBy(() -> service.update("fw-unsafe", request("旧配置", "", true, false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("填写新的飞书官方地址");
        verify(mapper, never()).updateById(unsafe);

        var repaired = service.update("fw-unsafe", request("已修复", URL, true, false));

        assertThat(repaired.name()).isEqualTo("已修复");
        assertThat(repaired.urlSet()).isTrue();
        assertThat(repaired.enabled()).isTrue();
        assertThat(repaired.urlMask()).endsWith("••••1234");
        verify(mapper).update(eq(unsafe), any());
    }

    @Test
    void businessTargetUsesTheSelectedEnabledConfigAndRejectsDisabledOne() {
        FeishuWebhookConfigEntity selected = entity("fw-energy", true, false, URL);
        selected.setName("精力补给群");
        when(mapper.selectById("fw-energy")).thenReturn(selected);

        var target = service.enabledTarget("fw-energy");

        assertThat(target.id()).isEqualTo("fw-energy");
        assertThat(target.name()).isEqualTo("精力补给群");
        assertThat(target.url()).isEqualTo(URL);

        selected.setEnabled(false);
        assertThatThrownBy(() -> service.enabledTarget("fw-energy"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("已停用")
                .hasMessageNotContaining("secret-token");
    }

    @Test
    void deletingDefaultPromotesARealRemainingConfigAndMirrorsItForRollingDeploy() {
        FeishuWebhookConfigEntity current = entity("fw-current", true, true, URL);
        FeishuWebhookConfigEntity remaining = entity(
                "fw-next", false, false, "https://open.larksuite.com/open-apis/bot/v2/hook/next-token-5678");
        when(mapper.selectById("fw-current")).thenReturn(current);
        when(mapper.selectList(any())).thenReturn(List.of(remaining));

        service.delete("fw-current");

        verify(mapper).delete(any());
        assertThat(remaining.getDefaultMarker()).isEqualTo("SYSTEM");
        verify(mapper).updateById(remaining);
        verify(settings).put(SettingService.FEISHU_WEBHOOK_URL,
                "https://open.larksuite.com/open-apis/bot/v2/hook/next-token-5678");
        verify(settings).put(SettingService.FEISHU_WEBHOOK_ENABLED, "false");
    }

    @Test
    void cannotSilentlyRemoveDefaultRoutingWhileEditing() {
        FeishuWebhookConfigEntity current = entity("fw-current", true, true, URL);
        when(mapper.selectById("fw-current")).thenReturn(current);

        assertThatThrownBy(() -> service.update("fw-current", request("默认", "", true, false)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("不能直接取消");
    }

    private SaveFeishuWebhookEndpointRequest request(
            String name, String url, boolean enabled, boolean systemDefault) {
        return new SaveFeishuWebhookEndpointRequest(name, " 用途 ", url, enabled, systemDefault);
    }

    private FeishuWebhookConfigEntity entity(String id, boolean enabled, boolean systemDefault, String url) {
        VaultCryptoService.Encrypted encrypted = crypto.encrypt(url);
        FeishuWebhookConfigEntity entity = new FeishuWebhookConfigEntity();
        entity.setId(id);
        entity.setName("机器人");
        entity.setDescription("用途");
        entity.setWebhookCiphertext(encrypted.ciphertext());
        entity.setWebhookNonce(encrypted.nonce());
        entity.setEnabled(enabled);
        entity.setDefaultMarker(systemDefault ? "SYSTEM" : null);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return entity;
    }

    private void assertUnreadableLegacySettingIsContained(RuntimeException failure) {
        FeishuWebhookConfigMapper localMapper = mock(FeishuWebhookConfigMapper.class);
        SettingService localSettings = mock(SettingService.class);
        AtomicReference<FeishuWebhookConfigEntity> stored = new AtomicReference<>();
        when(localMapper.selectCount(null)).thenAnswer(invocation -> stored.get() == null ? 0L : 1L);
        when(localSettings.get(SettingService.FEISHU_WEBHOOK_URL)).thenThrow(failure);
        when(localMapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation -> stored.get());
        when(localMapper.selectList(any())).thenAnswer(invocation ->
                stored.get() == null ? List.of() : List.of(stored.get()));
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        }).when(localMapper).insert(any(FeishuWebhookConfigEntity.class));
        FeishuWebhookConfigService localService = new FeishuWebhookConfigService(
                localMapper, crypto, localSettings, "");

        var legacyView = localService.defaultConfig();
        var result = localService.list();

        assertThat(legacyView).isPresent();
        assertThat(legacyView.orElseThrow().urlMask()).contains("重新填写");
        assertThat(result).singleElement().satisfies(config -> {
            assertThat(config.urlMask()).contains("重新填写");
            assertThat(config.description()).contains("无法读取").contains("重新填写");
            assertThat(config.urlSet()).isFalse();
            assertThat(config.enabled()).isFalse();
            assertThat(config.toString()).doesNotContain("legacy-secret-must-not-leak");
        });
        verify(localSettings).put(SettingService.FEISHU_WEBHOOK_REGISTRY_INITIALIZED, "true");
    }
}
