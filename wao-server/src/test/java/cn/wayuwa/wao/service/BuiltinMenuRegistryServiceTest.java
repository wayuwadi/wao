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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class BuiltinMenuRegistryServiceTest {

    @Test
    void oldRegistryAddsNotesBelowHomeWithoutOverwritingCustomMenus() throws Exception {
        var customized = new BuiltinMenuConfigDto("home", "我的首页", "ri-home-5-line", "ROOT", 10, true, "TAB");
        var old = new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(36, java.util.List.of(customized));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY))
                .thenReturn(new ObjectMapper().writeValueAsString(old));
        var menus = service.getRegistry().menus();
        assertThat(menus).filteredOn(menu -> "notes".equals(menu.id())).singleElement().satisfies(menu -> {
            assertThat(menu.label()).isEqualTo("小记");
            assertThat(menu.group()).isEqualTo("ROOT");
            assertThat(menu.order()).isEqualTo(15);
            assertThat(menu.visible()).isTrue();
        });
        assertThat(menus).filteredOn(menu -> "home".equals(menu.id())).singleElement()
                .satisfies(menu -> assertThat(menu.label()).isEqualTo("我的首页"));
    }

    private final SettingService settings = mock(SettingService.class);
    private final BuiltinMenuRegistryService service =
            new BuiltinMenuRegistryService(settings, new ObjectMapper().findAndRegisterModules());

    @Test
    void missingOrBrokenConfigFallsBackToActiveBuiltins() {
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(null);
        var menus = service.getRegistry().menus();
        assertThat(menus).noneMatch(menu -> "decisions".equals(menu.id()))
                .anyMatch(menu -> "projects".equals(menu.id()))
                .noneMatch(menu -> java.util.Set.of("chatgpt", "ai-sessions", "ledger", "updates",
                        "appearance", "food", "devices", "crawler-capabilities", "account-resources")
                        .contains(menu.id()));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn("{broken");
        assertThat(service.getRegistry().menus()).hasSameSizeAs(menus);
    }

    @Test
    void savesEditableMetadataWithoutAllowingIdsToChange() {
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(null);
        var menus = new ArrayList<>(service.getRegistry().menus());
        var calendar = menus.stream().filter(menu -> "calendar".equals(menu.id())).findFirst().orElseThrow();
        menus.set(menus.indexOf(calendar), new BuiltinMenuConfigDto(
                calendar.id(), "家庭日历", calendar.icon(), "PUBLIC", 1, true, "NEW_PAGE"));

        var saved = service.saveRegistry(new SaveBuiltinMenuRegistryRequest(menus));

        assertThat(saved.menus()).filteredOn(menu -> "calendar".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("家庭日历");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.order()).isEqualTo(1);
                    assertThat(menu.openMode()).isEqualTo("NEW_PAGE");
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
    void retiredPetWorldCannotReturnThroughOldRegistry() throws Exception {
        var stored = new ArrayList<>(service.getRegistry().menus());
        stored.add(new BuiltinMenuConfigDto("pet-world", "我的庭院", "ri-landscape-line", "TOOLS", 9, true, "TAB", "TOP"));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(43, stored)));
        assertThat(service.getRegistry().menus()).noneMatch(menu -> "pet-world".equals(menu.id()));
        assertThat(service.saveRegistry(new SaveBuiltinMenuRegistryRequest(stored)).menus())
                .noneMatch(menu -> "pet-world".equals(menu.id()));
    }

    @Test
    void upgradesOldRegistryWithoutLosingExistingCustomization() throws Exception {
        var oldMenus = new ArrayList<>(service.getRegistry().menus());
        var automation = oldMenus.stream().filter(menu -> "automation".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(automation), new BuiltinMenuConfigDto(
                automation.id(), "我的自动化", automation.icon(), automation.group(), automation.order(), false, "TAB"));
        oldMenus.add(new BuiltinMenuConfigDto("games", "游戏厅", "ri-gamepad-line", "TOOLS", 30, true, "TAB"));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(1, oldMenus)));
        var upgraded = service.getRegistry().menus();
        assertThat(upgraded).filteredOn(menu -> "automation".equals(menu.id())).singleElement()
                .satisfies(menu -> assertThat(menu.label()).isEqualTo("我的自动化"));
        assertThat(upgraded).noneMatch(menu -> "games".equals(menu.id()));
    }

    @Test
    void upgradesOnlyTheUntouchedNavigationLabelToWorkbench() throws Exception {
        var oldMenus = new ArrayList<>(service.getRegistry().menus());
        var navigation = oldMenus.stream().filter(menu -> "navigation".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(navigation), new BuiltinMenuConfigDto(
                navigation.id(), "导航", navigation.icon(), navigation.group(), navigation.order(), true, "TAB"));
        var oldPayload = new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(24, oldMenus);
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY))
                .thenReturn(new ObjectMapper().writeValueAsString(oldPayload));

        assertThat(service.getRegistry().menus()).filteredOn(menu -> "navigation".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("工作台"));

        oldMenus.set(oldMenus.indexOf(oldMenus.stream()
                        .filter(menu -> "navigation".equals(menu.id())).findFirst().orElseThrow()),
                new BuiltinMenuConfigDto("navigation", "我的入口", "ri-compass-discover-line",
                        "ROOT", 20, true, "TAB"));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(24, oldMenus)));
        assertThat(service.getRegistry().menus()).filteredOn(menu -> "navigation".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("我的入口"));
    }

    @Test
    void oldAppearanceEntryIsRetired() throws Exception {
        var oldMenus = new ArrayList<>(service.getRegistry().menus());
        oldMenus.add(new BuiltinMenuConfigDto("appearance", "形象管理", "ri-shirt-line",
                "PERSONAL", 17, true, "TAB"));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(35, oldMenus)));
        assertThat(service.getRegistry().menus()).noneMatch(menu -> "appearance".equals(menu.id()));
    }

    @Test
    void rejectsUnknownOpenMode() {
        var menus = new ArrayList<>(service.getRegistry().menus());
        var home = menus.get(0);
        menus.set(0, new BuiltinMenuConfigDto(
                home.id(), home.label(), home.icon(), home.group(), home.order(), home.visible(), "POPUP"));

        assertThatThrownBy(() -> service.saveRegistry(new SaveBuiltinMenuRegistryRequest(menus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("打开方式");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCK", "TOP"})
    void savesDockPlacementAndDefaultsMissingPlacementToMenu(String placement) throws Exception {
        var menus = new ArrayList<>(service.getRegistry().menus());
        var projects = menus.stream().filter(menu -> "projects".equals(menu.id())).findFirst().orElseThrow();
        menus.set(menus.indexOf(projects), new BuiltinMenuConfigDto(
                projects.id(), projects.label(), projects.icon(), projects.group(), projects.order(),
                projects.visible(), projects.openMode(), placement));

        assertThat(service.saveRegistry(new SaveBuiltinMenuRegistryRequest(menus)).menus())
                .filteredOn(menu -> "projects".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.placement()).isEqualTo(placement));

        var legacyMenus = menus.stream().map(menu -> new BuiltinMenuConfigDto(
                menu.id(), menu.label(), menu.icon(), menu.group(), menu.order(), menu.visible(),
                menu.openMode(), null)).toList();
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(25, legacyMenus)));
        assertThat(service.getRegistry().menus()).allSatisfy(menu -> assertThat(menu.placement()).isEqualTo("MENU"));

        var invalid = new ArrayList<>(service.getRegistry().menus());
        var home = invalid.get(0);
        invalid.set(0, new BuiltinMenuConfigDto(
                home.id(), home.label(), home.icon(), home.group(), home.order(), home.visible(),
                home.openMode(), "FLOAT"));
        assertThatThrownBy(() -> service.saveRegistry(new SaveBuiltinMenuRegistryRequest(invalid)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("展示位置");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DOCK", "TOP"})
    void oldClientSaveWithoutPlacementPreservesDockedMenu(String placement) throws Exception {
        var storedMenus = new ArrayList<>(service.getRegistry().menus());
        var projects = storedMenus.stream().filter(menu -> "projects".equals(menu.id())).findFirst().orElseThrow();
        storedMenus.set(storedMenus.indexOf(projects), new BuiltinMenuConfigDto(
                projects.id(), projects.label(), projects.icon(), projects.group(), projects.order(),
                projects.visible(), projects.openMode(), placement));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(26, storedMenus)));
        var oldClientMenus = storedMenus.stream().map(menu -> new BuiltinMenuConfigDto(
                menu.id(), menu.label(), menu.icon(), menu.group(), menu.order(), menu.visible(),
                menu.openMode(), null)).toList();

        assertThat(service.saveRegistry(new SaveBuiltinMenuRegistryRequest(oldClientMenus)).menus())
                .filteredOn(menu -> "projects".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.placement()).isEqualTo(placement));
    }
}
