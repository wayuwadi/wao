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
        assertThat(service.getRegistry().menus()).hasSize(24);
        assertThat(service.getRegistry().menus()).filteredOn(menu -> "skills".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("Skill 市场");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.visible()).isTrue();
                    assertThat(menu.openMode()).isEqualTo("TAB");
                });

        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY)).thenReturn("{broken");
        assertThat(service.getRegistry().menus()).hasSize(24);
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
        oldMenus.removeIf(menu -> "tianren".equals(menu.id()));
        oldMenus.removeIf(menu -> "life-review".equals(menu.id()));
        oldMenus.removeIf(menu -> "chat-reviews".equals(menu.id()));
        oldMenus.removeIf(menu -> "skills".equals(menu.id()));
        oldMenus.removeIf(menu -> "ideas".equals(menu.id()));
        oldMenus.removeIf(menu -> "request-monitoring".equals(menu.id()));
        oldMenus.removeIf(menu -> "system-reports".equals(menu.id()));
        var automation = oldMenus.stream().filter(menu -> "automation".equals(menu.id())).findFirst().orElseThrow();
        oldMenus.set(oldMenus.indexOf(automation), new BuiltinMenuConfigDto(
                automation.id(), "我的自动化", automation.icon(), automation.group(), automation.order(), false, null));
        var oldPayload = new cn.wayuwa.wao.dto.BuiltinMenuRegistryDto(1, oldMenus);
        when(settings.get(SettingService.BUILTIN_MENU_REGISTRY))
                .thenReturn(new ObjectMapper().writeValueAsString(oldPayload));

        var upgraded = service.getRegistry();

        assertThat(upgraded.menus()).hasSize(24);
        assertThat(upgraded.menus()).filteredOn(menu -> "automation".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("我的自动化");
                    assertThat(menu.visible()).isFalse();
                    assertThat(menu.openMode()).isEqualTo("TAB");
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
        assertThat(upgraded.menus()).filteredOn(menu -> "chat-reviews".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("聊天复盘");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "skills".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("Skill 市场");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "ideas".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("点子库");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                    assertThat(menu.order()).isEqualTo(11);
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "request-monitoring".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("请求监控");
                    assertThat(menu.group()).isEqualTo("TOOLS");
                    assertThat(menu.visible()).isTrue();
                });
        assertThat(upgraded.menus()).filteredOn(menu -> "system-reports".equals(menu.id()))
                .singleElement().satisfies(menu -> {
                    assertThat(menu.label()).isEqualTo("系统报告");
                    assertThat(menu.group()).isEqualTo("PUBLIC");
                });
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
}
