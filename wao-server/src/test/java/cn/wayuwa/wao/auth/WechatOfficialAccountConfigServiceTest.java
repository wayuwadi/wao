package cn.wayuwa.wao.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.SaveWechatOfficialLoginConfigRequest;
import cn.wayuwa.wao.service.SettingService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WechatOfficialAccountConfigServiceTest {
    private final SettingService settings = mock(SettingService.class);
    private final WechatOfficialLoginStore store = mock(WechatOfficialLoginStore.class);
    private final Map<String, String> values = new HashMap<>();
    private final WechatOfficialAccountProperties deployment = new WechatOfficialAccountProperties(
            false, "", "", "", "https://api.weixin.qq.com", "https://mp.weixin.qq.com");
    private final WechatOfficialAccountConfigService service =
            new WechatOfficialAccountConfigService(settings, deployment, store);

    @BeforeEach
    void backSettingsWithMemory() {
        when(settings.getAll(anySet())).thenAnswer(ignored -> new HashMap<>(values));
        doAnswer(invocation -> {
            String value = invocation.getArgument(1);
            if (value != null) values.put(invocation.getArgument(0), value);
            return null;
        }).when(settings).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test
    void fallsBackToDeploymentConfigurationUntilSystemSettingsAreSaved() {
        var configuredDeployment = new WechatOfficialAccountProperties(
                true, "wx-env", "env-secret", "env-token", "https://api.weixin.qq.com",
                "https://mp.weixin.qq.com");
        var fallback = new WechatOfficialAccountConfigService(settings, configuredDeployment, store);

        var result = fallback.get();

        assertThat(result.enabled()).isTrue();
        assertThat(result.appId()).isEqualTo("wx-env");
        assertThat(result.configured()).isTrue();
        assertThat(result.appSecretMask()).doesNotContain("env-secret");
        assertThat(result.tokenMask()).doesNotContain("env-token");
    }

    @Test
    void savesCompleteConfigurationAndReturnsOnlyMasks() {
        var result = service.save(new SaveWechatOfficialLoginConfigRequest(
                true, " wx-system ", " system-secret ", " callback-token "));

        assertThat(result.enabled()).isTrue();
        assertThat(result.appId()).isEqualTo("wx-system");
        assertThat(result.configured()).isTrue();
        assertThat(result.appSecretMask()).doesNotContain("system-secret");
        assertThat(result.tokenMask()).doesNotContain("callback-token");
        verify(store).clearAccessToken();
        verify(settings).put(SettingService.WECHAT_OFFICIAL_APP_SECRET, "system-secret");
        verify(settings).put(SettingService.WECHAT_OFFICIAL_TOKEN, "callback-token");
    }

    @Test
    void blankSecretFieldsKeepExistingValuesWhileOtherFieldsChange() {
        values.put(SettingService.WECHAT_OFFICIAL_ENABLED, "true");
        values.put(SettingService.WECHAT_OFFICIAL_APP_ID, "wx-old");
        values.put(SettingService.WECHAT_OFFICIAL_APP_SECRET, "old-secret");
        values.put(SettingService.WECHAT_OFFICIAL_TOKEN, "old-token");

        var result = service.save(new SaveWechatOfficialLoginConfigRequest(true, "wx-new", "", null));

        assertThat(result.appId()).isEqualTo("wx-new");
        assertThat(result.appSecretSet()).isTrue();
        assertThat(result.tokenSet()).isTrue();
        verify(settings).put(SettingService.WECHAT_OFFICIAL_APP_SECRET, null);
        verify(settings).put(SettingService.WECHAT_OFFICIAL_TOKEN, null);
    }

    @Test
    void refusesToEnableIncompleteConfigurationWithoutPartialWrites() {
        assertThatThrownBy(() -> service.save(
                new SaveWechatOfficialLoginConfigRequest(true, "wx-app", "", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完整填写");

        verify(settings, never()).put(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        verify(store, never()).clearAccessToken();
    }
}
