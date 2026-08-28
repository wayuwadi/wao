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
        assertThat(service.getRegistry().menus()).hasSize(25);
        assertThat(service.getRegistry().menus()).filteredOn(menu -> "skills".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("Skill 市场");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.visible()).isFalse();
                    assertThat(menu.openMode()).isEqualTo("TAB");
                });

        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn("{broken");
        assertThat(service.getRegistry().menus()).hasSize(25);
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
    void upgradesOldRegistryWithoutLosingExistingCustomization() throws Exception {
        var oldMenus = new ArrayList<>(service.getRegistry().menus());
        oldMenus.removeIf(menu -> "navigation".equals(menu.id()));
        oldMenus.removeIf(menu -> "tianren".equals(menu.id()));
        oldMenus.removeIf(menu -> "life-review".equals(menu.id()));
        oldMenus.removeIf(menu -> "skills".equals(menu.id()));
        oldMenus.removeIf(menu -> "skill-connections".equals(menu.id()));
        oldMenus.removeIf(menu -> "request-monitoring".equals(menu.id()));
        oldMenus.removeIf(menu -> "dashboard".equals(menu.id()));
        var automation = oldMenus.stream().filter(menu -> "automation".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(automation), new BuiltinMenuConfigDto(
                automation.id(), "我的自动化", automation.icon(), automation.group(), automation.order(), false, null));
        var games = oldMenus.stream().filter(menu -> "games".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(games), new BuiltinMenuConfigDto(
                games.id(), games.label(), games.icon(), games.group(), games.order(), true, games.openMode()));
        var drawings = oldMenus.stream().filter(menu -> "drawings".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(drawings), new BuiltinMenuConfigDto(
                drawings.id(), "我的草图", drawings.icon(), drawings.group(), drawings.order(), true, drawings.openMode()));
        // 模拟 V17 注册表中仍存在、V18 已退役的点子库入口。
        oldMenus.add(new BuiltinMenuConfigDto("ideas", "我的旧点子入口", "ri-lightbulb-flash-line",
                "PUBLIC", 11, true, "TAB"));
        oldMenus.add(new BuiltinMenuConfigDto("chat", "我的旧聊天入口", "ri-chat-3-line",
                "PUBLIC", 14, true, "TAB"));
        oldMenus.add(new BuiltinMenuConfigDto("chat-reviews", "旧聊天复盘入口", "ri-chat-history-line",
                "PUBLIC", 15, true, "TAB"));
        oldMenus.add(new BuiltinMenuConfigDto("system-reports", "我的旧报告入口", "ri-bar-chart-box-line",
                "PUBLIC", 16, true, "TAB"));
        var oldPayload = new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(1, oldMenus);
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY))
                .thenReturn(new ObjectMapper().writeValueAsString(oldPayload));

        var upgraded = service.getRegistry();

        assertThat(upgraded.menus()).hasSize(25);
        assertThat(upgraded.menus()).filteredOn(menu -> "navigation".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("工作台");
                    assertThat(menu.group()).isEqualTo("ROOT");
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "automation".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("我的自动化");
                    assertThat(menu.visible()).isFalse();
                    assertThat(menu.openMode()).isEqualTo("TAB");
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "games".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.visible()).isFalse());
        assertThat(upgraded.menus()).filteredOn(menu -> "drawings".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("我的草图");
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "tianren".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.group()).isEqualTo("PERSONAL");
                    assertThat(menu.label()).isEqualTo("天人一卦");
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "vocabulary".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("单词本"));
        assertThat(upgraded.menus()).filteredOn(menu -> "english-learning".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("英语训练"));
        assertThat(upgraded.menus()).filteredOn(menu -> "weekly-report".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("周报生成"));
        assertThat(upgraded.menus()).filteredOn(menu -> "life-review".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("人生回顾"));
        assertThat(upgraded.menus()).filteredOn(menu -> "workday-rhythm".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("今日节奏"));
        assertThat(upgraded.menus()).filteredOn(menu -> "memory".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("个人记忆"));
        assertThat(upgraded.menus()).filteredOn(menu -> "workspaces".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.label()).isEqualTo("工作台"));
        assertThat(upgraded.menus()).noneMatch(menu -> "chat-reviews".equals(menu.id()));
        assertThat(upgraded.menus()).noneMatch(menu -> "chat".equals(menu.id()));
        assertThat(upgraded.menus()).filteredOn(menu -> "skills".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("Skill 市场");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.visible()).isFalse();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "skill-connections".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("Skill 接入");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.visible()).isFalse();
                });
        assertThat(upgraded.menus()).noneMatch(menu -> "ideas".equals(menu.id()));
        assertThat(upgraded.menus()).filteredOn(menu -> "request-monitoring".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("请求监控");
                    assertThat(menu.group()).isEqualTo("TOOLS");
                    assertThat(menu.visible()).isFalse();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "dashboard".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("使用统计");
                    assertThat(menu.group()).isEqualTo("TOOLS");
                    assertThat(menu.openMode()).isEqualTo("TAB");
                    assertThat(menu.visible()).isFalse();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "data-center".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("数据中心");
                    assertThat(menu.group()).isEqualTo("TOOLS");
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).noneMatch(menu -> "system-reports".equals(menu.id()));
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
    void rejectsUnknownOpenMode() {
        var menus = new ArrayList<>(service.getRegistry().menus());
        var home = menus.get(0);
        menus.set(0, new BuiltinMenuConfigDto(
                home.id(), home.label(), home.icon(), home.group(), home.order(), home.visible(), "POPUP"));

        assertThatThrownBy(() -> service.saveRegistry(new SaveBuiltinMenuRegistryRequest(menus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("打开方式");
    }

    @Test
    void savesDockPlacementAndDefaultsMissingPlacementToMenu() throws Exception {
        var menus = new ArrayList<>(service.getRegistry().menus());
        var projects = menus.stream().filter(menu -> "projects".equals(menu.id())).findFirst().orElseThrow();
        menus.set(menus.indexOf(projects), new BuiltinMenuConfigDto(
                projects.id(), projects.label(), projects.icon(), projects.group(), projects.order(),
                projects.visible(), projects.openMode(), "DOCK"));

        assertThat(service.saveRegistry(new SaveBuiltinMenuRegistryRequest(menus)).menus())
                .filteredOn(menu -> "projects".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.placement()).isEqualTo("DOCK"));

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

    @Test
    void oldClientSaveWithoutPlacementPreservesDockedMenu() throws Exception {
        var storedMenus = new ArrayList<>(service.getRegistry().menus());
        var projects = storedMenus.stream().filter(menu -> "projects".equals(menu.id())).findFirst().orElseThrow();
        storedMenus.set(storedMenus.indexOf(projects), new BuiltinMenuConfigDto(
                projects.id(), projects.label(), projects.icon(), projects.group(), projects.order(),
                projects.visible(), projects.openMode(), "DOCK"));
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn(new ObjectMapper().writeValueAsString(
                new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(26, storedMenus)));
        var oldClientMenus = storedMenus.stream().map(menu -> new BuiltinMenuConfigDto(
                menu.id(), menu.label(), menu.icon(), menu.group(), menu.order(), menu.visible(),
                menu.openMode(), null)).toList();

        assertThat(service.saveRegistry(new SaveBuiltinMenuRegistryRequest(oldClientMenus)).menus())
                .filteredOn(menu -> "projects".equals(menu.id()))
                .singleElement().satisfies(menu -> assertThat(menu.placement()).isEqualTo("DOCK"));
    }
}
