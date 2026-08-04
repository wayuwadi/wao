package cn.wayuwa.wao.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.wayuwa.wao.dto.BuiltinMenuConfigDto;
import cn.wayuwa.wao.dto.SaveBuiltinMenuRegistryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BuiltinMenuRegistryServiceTest {

    private final SettingService settings = mock(SettingService.class);
    private final BuiltinMenuRegistryService service =
            new BuiltinMenuRegistryService(settings, new ObjectMapper().findAndRegisterModules());

    @Test
    void missingOrBrokenConfigFallsBackToAllBuiltins() {
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(null);
        assertThat(service.getRegistry().menus()).hasSize(9);

        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn("{broken");
        assertThat(service.getRegistry().menus()).hasSize(9);
    }

    @Test
    void savesEditableMetadataWithoutAllowingIdsToChange() {
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(null);
        var menus = new ArrayList<>(service.getRegistry().menus());
        var calendar = menus.stream().filter(menu -> "calendar".equals(menu.id())).findFirst().orElseThrow();
        menus.set(menus.indexOf(calendar), new BuiltinMenuConfigDto(
                calendar.id(), "家庭日历", calendar.icon(), "PUBLIC", 1, true));

        var saved = service.saveRegistry(new SaveBuiltinMenuRegistryRequest(menus));

        assertThat(saved.menus()).filteredOn(menu -> "calendar".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("家庭日历");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.order()).isEqualTo(1);
                });
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(settings).put(eq(SettingService.BUILTIN_MENU_REGISTRY), payload.capture());
        assertThat(payload.getValue()).contains("家庭日历");
    }

    @Test
    void rejectsMissingOrUnknownBuiltins() {
        assertThatThrownBy(() -> service.saveRegistry(new SaveBuiltinMenuRegistryRequest(java.util.List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数量");
    }

    @Test
    void upgradesOldRegistryWithoutLosingExistingCustomization() throws Exception {
        var oldMenus = new ArrayList<>(service.getRegistry().menus());
        oldMenus.removeIf(menu -> "games".equals(menu.id()));
        var automation = oldMenus.stream().filter(menu -> "automation".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(automation), new BuiltinMenuConfigDto(
                automation.id(), "我的自动化", automation.icon(), automation.group(), automation.order(), false));
        var oldPayload = new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(1, oldMenus);
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY))
                .thenReturn(new ObjectMapper().writeValueAsString(oldPayload));

        var upgraded = service.getRegistry();

        assertThat(upgraded.menus()).hasSize(9);
        assertThat(upgraded.menus()).filteredOn(menu -> "automation".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("我的自动化");
                    assertThat(menu.visible()).isFalse();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "games".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.group()).isEqualTo("TOOLS"));
    }
}
