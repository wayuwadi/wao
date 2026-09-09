package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.ExternalAppConfigDto;
import cn.wayuwa.wao.dto.ExternalAppRegistryDto;
import cn.wayuwa.wao.dto.SaveExternalAppConfigRequest;
import cn.wayuwa.wao.dto.SaveExternalAppRegistryRequest;
import cn.wayuwa.wao.dto.SaveMediaAgentMenuConfigRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ExternalAppRegistryServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExternalAppRegistryService service = new ExternalAppRegistryService(settings, json);

    @Test
    void firstReadMigratesExistingMediaAgentMenuWithoutWriting() {
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn(null);
        when(settings.get(SettingService.MEDIA_AGENT_MENU_LABEL, "自媒体 Agent")).thenReturn("内容助手");
        when(settings.get(SettingService.MEDIA_AGENT_MENU_GROUP, "PUBLIC")).thenReturn("TOOLS");
        when(settings.get(SettingService.MEDIA_AGENT_MENU_ORDER, "20")).thenReturn("5");
        when(settings.get(SettingService.MEDIA_AGENT_MENU_VISIBLE, "true")).thenReturn("false");

        var registry = service.getRegistry();

        assertThat(registry.version()).isEqualTo(8);
        assertThat(registry.apps()).hasSize(4);
        assertThat(registry.apps().get(0)).satisfies(app -> {
            assertThat(app.id()).isEqualTo("media-agent");
            assertThat(app.label()).isEqualTo("内容助手");
            assertThat(app.group()).isEqualTo("TOOLS");
            assertThat(app.order()).isEqualTo(5);
            assertThat(app.visible()).isFalse();
            assertThat(app.appUrl()).isEqualTo("/agent/");
            assertThat(app.healthUrl()).isEqualTo("/api/agent/health");
            assertThat(app.openMode()).isEqualTo("TAB");
            assertThat(app.placement()).isEqualTo("MENU");
        });
        assertThat(registry.apps().get(1)).satisfies(app -> {
            assertThat(app.id()).isEqualTo("getnote");
            assertThat(app.label()).isEqualTo("得到大脑");
            assertThat(app.group()).isEqualTo("TOOLS");
            assertThat(app.appUrl()).isEqualTo("https://www.biji.com/");
            assertThat(app.healthUrl()).isEmpty();
            assertThat(app.openMode()).isEqualTo("NEW_PAGE");
        });
        assertThat(registry.apps().get(2)).satisfies(app -> {
            assertThat(app.id()).isEqualTo("jumpserver");
            assertThat(app.label()).isEqualTo("资源管理");
            assertThat(app.group()).isEqualTo("TOOLS");
            assertThat(app.appUrl()).isEqualTo("/api/auth/jumpserver");
            assertThat(app.healthUrl()).isEqualTo("/api/external/jumpserver/health");
            assertThat(app.openMode()).isEqualTo("NEW_PAGE");
        });
    }

    @Test
    void savesAndReadsMultipleApplications() {
        var request = new SaveExternalAppRegistryRequest(List.of(
                app("media-agent", "自媒体 Agent", "/agent/", "/api/agent/health"),
                app("wao-insight", "群聊洞察", "/insight/", "/api/insight/health"),
                app("data-studio", "数据工作台", "https://studio.example.com/", "")));

        var saved = service.saveRegistry(request);

        assertThat(saved.apps()).hasSize(2);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(settings).put(eq(SettingService.EXTERNAL_APP_REGISTRY), jsonCaptor.capture());
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn(jsonCaptor.getValue());
        assertThat(service.getRegistry().apps()).extracting("id")
                .containsExactly("media-agent", "data-studio");
    }

    @Test
    void filtersRetiredInsightFromPersistedRegistry() throws Exception {
        String stored = json.writeValueAsString(new ExternalAppRegistryDto(1, List.of(
                new ExternalAppConfigDto(
                        "media-agent", "自媒体 Agent", "ri-sparkling-2-line", "PUBLIC", 20, true,
                        "/agent/", "/api/agent/health", null),
                new ExternalAppConfigDto(
                        "wao-insight", "群聊洞察", "ri-bubble-chart-line", "PUBLIC", 25, true,
                        "/insight/", "/api/insight/health", null))));
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn(stored);

        assertThat(service.getRegistry().apps()).extracting("id")
                .containsExactly("media-agent", "getnote", "jumpserver", "weknora");
        assertThat(service.getRegistry().apps().get(0).openMode()).isEqualTo("TAB");
    }

    @Test
    void upgradesVersionThreeRegistryOnceAndRespectsLaterRemoval() throws Exception {
        var mediaAgent = new ExternalAppConfigDto(
                "media-agent", "自媒体 Agent", "ri-sparkling-2-line", "PUBLIC", 20, true,
                "/agent/", "/api/agent/health", "TAB");
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(3, List.of(mediaAgent))));

        assertThat(service.getRegistry().apps()).extracting("id")
                .containsExactly("media-agent", "getnote", "jumpserver", "weknora");

        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(4, List.of(mediaAgent))));
        assertThat(service.getRegistry().apps()).extracting("id")
                .containsExactly("media-agent", "jumpserver", "weknora");

        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(5, List.of(mediaAgent))));
        assertThat(service.getRegistry().apps()).extracting("id")
                .containsExactly("media-agent", "weknora");
    }

    @Test
    void introducesWeKnoraForExistingUsersAndHonorsRemovalAfterUpgrade() throws Exception {
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(7, List.of())));
        assertThat(service.getRegistry().apps()).singleElement().satisfies(app -> {
            assertThat(app.id()).isEqualTo("weknora");
            assertThat(app.appUrl()).isEqualTo("/weknora/");
            assertThat(app.openMode()).isEqualTo("TAB");
        });
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(8, List.of())));
        assertThat(service.getRegistry().apps()).isEmpty();
    }

    @Test
    void savesNewPageModeAndRejectsUnknownMode() {
        var newPage = new SaveExternalAppConfigRequest(
                "data-studio", "数据工作台", "ri-apps-2-line", "PUBLIC", 20, true,
                "https://studio.example.com/", "", "NEW_PAGE");
        assertThat(service.saveRegistry(new SaveExternalAppRegistryRequest(List.of(newPage))).apps())
                .singleElement().satisfies(app -> assertThat(app.openMode()).isEqualTo("NEW_PAGE"));

        var invalid = new SaveExternalAppConfigRequest(
                "data-studio", "数据工作台", "ri-apps-2-line", "PUBLIC", 20, true,
                "https://studio.example.com/", "", "POPUP");
        assertThatThrownBy(() -> service.saveRegistry(new SaveExternalAppRegistryRequest(List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("打开方式");
    }

    @Test
    void repairsHistoricalJumpServerDirectUrlWithoutOverwritingMenuPreferences() throws Exception {
        var direct = new ExternalAppConfigDto(
                "jumpserver", "我的主机", "ri-server-line", "PERSONAL", 7, false,
                "https://jms.wayuwa.cn/", "https://jms.wayuwa.cn/api/health/", "TAB", "DOCK");
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(6, List.of(direct))));

        assertThat(service.getRegistry()).satisfies(registry -> {
            assertThat(registry.version()).isEqualTo(8);
            assertThat(registry.apps()).filteredOn(app -> "jumpserver".equals(app.id())).singleElement().satisfies(app -> {
                assertThat(app.label()).isEqualTo("我的主机");
                assertThat(app.group()).isEqualTo("PERSONAL");
                assertThat(app.order()).isEqualTo(7);
                assertThat(app.visible()).isFalse();
                assertThat(app.openMode()).isEqualTo("TAB");
                assertThat(app.placement()).isEqualTo("DOCK");
                assertThat(app.appUrl()).isEqualTo("/api/auth/jumpserver");
                assertThat(app.healthUrl()).isEqualTo("/api/external/jumpserver/health");
            });
        });
    }

    @Test
    void keepsJumpServerIdentityExchangeEndpointWhenRegistryIsSaved() {
        var direct = new SaveExternalAppConfigRequest(
                "jumpserver", "资源管理", "ri-server-line", "TOOLS", 15, true,
                "https://jms.wayuwa.cn/", "https://jms.wayuwa.cn/api/health/", "NEW_PAGE");

        assertThat(service.saveRegistry(new SaveExternalAppRegistryRequest(List.of(direct))).apps())
                .singleElement().satisfies(app -> {
                    assertThat(app.appUrl()).isEqualTo("/api/auth/jumpserver");
                    assertThat(app.healthUrl()).isEqualTo("/api/external/jumpserver/health");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCK", "TOP"})
    void savesDockPlacementAndDefaultsMissingPlacementToMenu(String placement) throws Exception {
        var docked = new SaveExternalAppConfigRequest(
                "data-studio", "数据工作台", "ri-apps-2-line", "PUBLIC", 20, true,
                "https://studio.example.com/", "", "TAB", placement);
        assertThat(service.saveRegistry(new SaveExternalAppRegistryRequest(List.of(docked))).apps())
                .singleElement().satisfies(app -> assertThat(app.placement()).isEqualTo(placement));

        var legacy = new ExternalAppConfigDto(
                "media-agent", "自媒体 Agent", "ri-sparkling-2-line", "PUBLIC", 20, true,
                "/agent/", "/api/agent/health", "TAB", null);
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(5, List.of(legacy))));
        assertThat(service.getRegistry().apps()).filteredOn(app -> "media-agent".equals(app.id())).singleElement()
                .satisfies(app -> assertThat(app.placement()).isEqualTo("MENU"));

        var invalid = new SaveExternalAppConfigRequest(
                "data-studio", "数据工作台", "ri-apps-2-line", "PUBLIC", 20, true,
                "https://studio.example.com/", "", "TAB", "FLOAT");
        assertThatThrownBy(() -> service.saveRegistry(new SaveExternalAppRegistryRequest(List.of(invalid))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("展示位置");
    }

    @Test
    void oldClientSaveWithoutPlacementPreservesDockedApplication() throws Exception {
        var stored = new ExternalAppConfigDto(
                "media-agent", "自媒体 Agent", "ri-sparkling-2-line", "PUBLIC", 20, true,
                "/agent/", "/api/agent/health", "TAB", "DOCK");
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY))
                .thenReturn(json.writeValueAsString(new ExternalAppRegistryDto(6, List.of(stored))));
        var oldClient = new SaveExternalAppConfigRequest(
                stored.id(), stored.label(), stored.icon(), stored.group(), stored.order(), stored.visible(),
                stored.appUrl(), stored.healthUrl(), stored.openMode(), null);

        assertThat(service.saveRegistry(new SaveExternalAppRegistryRequest(List.of(oldClient))).apps())
                .singleElement().satisfies(app -> assertThat(app.placement()).isEqualTo("DOCK"));
    }

    @Test
    void legacyMediaAgentUpdatePreservesOpenMode() throws Exception {
        var stored = new ExternalAppRegistryDto(3, List.of(new ExternalAppConfigDto(
                "media-agent", "自媒体 Agent", "ri-sparkling-2-line", "PUBLIC", 20, true,
                "/agent/", "/api/agent/health", "NEW_PAGE")));
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn(json.writeValueAsString(stored));
        clearInvocations(settings);

        service.saveLegacyMediaAgentMenu(new SaveMediaAgentMenuConfigRequest("内容助手", "TOOLS", 5, true));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(settings).put(eq(SettingService.EXTERNAL_APP_REGISTRY), payload.capture());
        assertThat(json.readValue(payload.getValue(), ExternalAppRegistryDto.class).apps())
                .filteredOn(app -> "media-agent".equals(app.id()))
                .singleElement().satisfies(app -> assertThat(app.openMode()).isEqualTo("NEW_PAGE"));
    }

    @Test
    void rejectsDuplicateIdsAndUnsafeUrls() {
        var duplicate = new SaveExternalAppRegistryRequest(List.of(
                app("same", "应用一", "/one/", ""),
                app("same", "应用二", "/two/", "")));
        assertThatThrownBy(() -> service.saveRegistry(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能重复");

        var unsafe = new SaveExternalAppRegistryRequest(List.of(
                app("unsafe", "不安全应用", "javascript:alert(1)", "")));
        assertThatThrownBy(() -> service.saveRegistry(unsafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void explicitEmptyRegistryDoesNotRestoreDefaultApplication() {
        service.saveRegistry(new SaveExternalAppRegistryRequest(List.of()));
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(settings).put(eq(SettingService.EXTERNAL_APP_REGISTRY), jsonCaptor.capture());
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn(jsonCaptor.getValue());

        assertThat(service.getRegistry().apps()).isEmpty();
    }

    @Test
    void rejectsMoreThanThirtyApplicationsInsideBusinessService() {
        var apps = IntStream.rangeClosed(1, 31)
                .mapToObj(index -> app("app-" + index, "应用" + index, "/app-" + index + "/", ""))
                .toList();

        assertThatThrownBy(() -> service.saveRegistry(new SaveExternalAppRegistryRequest(apps)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能超过 30 个");
    }

    private static SaveExternalAppConfigRequest app(
            String id, String label, String appUrl, String healthUrl) {
        return new SaveExternalAppConfigRequest(
                id, label, "ri-apps-2-line", "PUBLIC", 20, true, appUrl, healthUrl, "TAB");
    }
}
