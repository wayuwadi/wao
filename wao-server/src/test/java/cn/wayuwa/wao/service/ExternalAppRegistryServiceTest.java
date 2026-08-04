package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.SaveExternalAppConfigRequest;
import cn.wayuwa.wao.dto.SaveExternalAppRegistryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExternalAppRegistryServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final ExternalAppRegistryService service = new ExternalAppRegistryService(settings, json);

    @Test
    void firstReadIncludesInsightAndMigratesExistingMediaAgentMenuWithoutWriting() {
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn(null);
        when(settings.get(SettingService.MEDIA_AGENT_MENU_LABEL, "自媒体 Agent")).thenReturn("内容助手");
        when(settings.get(SettingService.MEDIA_AGENT_MENU_GROUP, "PUBLIC")).thenReturn("TOOLS");
        when(settings.get(SettingService.MEDIA_AGENT_MENU_ORDER, "20")).thenReturn("5");
        when(settings.get(SettingService.MEDIA_AGENT_MENU_VISIBLE, "true")).thenReturn("false");

        var registry = service.getRegistry();

        assertThat(registry.version()).isEqualTo(2);
        assertThat(registry.apps()).hasSize(2);
        assertThat(registry.apps().get(0)).satisfies(app -> {
            assertThat(app.id()).isEqualTo("media-agent");
            assertThat(app.label()).isEqualTo("内容助手");
            assertThat(app.group()).isEqualTo("TOOLS");
            assertThat(app.order()).isEqualTo(5);
            assertThat(app.visible()).isFalse();
            assertThat(app.appUrl()).isEqualTo("/agent/");
            assertThat(app.healthUrl()).isEqualTo("/api/agent/health");
        });
        assertThat(registry.apps().get(1)).satisfies(app -> {
            assertThat(app.id()).isEqualTo("wao-insight");
            assertThat(app.label()).isEqualTo("娃的洞见");
            assertThat(app.appUrl()).isEqualTo("/insight/");
            assertThat(app.healthUrl()).isEmpty();
        });
    }

    @Test
    void upgradesVersionOneRegistryByAppendingInsightOnce() throws Exception {
        when(settings.get(SettingService.EXTERNAL_APP_REGISTRY)).thenReturn("""
                {"version":1,"apps":[{
                  "id":"data-studio","label":"数据工作台","icon":"ri-apps-2-line",
                  "group":"TOOLS","order":10,"visible":true,"appUrl":"/studio/","healthUrl":""
                }]}
                """);

        var registry = service.getRegistry();

        assertThat(registry.version()).isEqualTo(2);
        assertThat(registry.apps()).extracting("id").containsExactly("data-studio", "wao-insight");
    }

    @Test
    void savesAndReadsMultipleApplications() {
        var request = new SaveExternalAppRegistryRequest(List.of(
                app("media-agent", "自媒体 Agent", "/agent/", "/api/agent/health"),
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
                id, label, "ri-apps-2-line", "PUBLIC", 20, true, appUrl, healthUrl);
    }
}
