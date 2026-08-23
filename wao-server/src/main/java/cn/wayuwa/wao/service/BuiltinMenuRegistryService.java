package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.BuiltinMenuConfigDto;
import cn.wayuwa.wao.dto.BuiltinMenuRegistryDto;
import cn.wayuwa.wao.dto.SaveBuiltinMenuRegistryRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 维护代码内置菜单的可编辑元数据；稳定 id 与路由仍由前端代码保证。 */
@Service
public class BuiltinMenuRegistryService {

    private static final int VERSION = 15;
    private static final Pattern ICON = Pattern.compile("ri-[a-z0-9-]+-(?:line|fill)");
    private static final Set<String> GROUPS = Set.of("ROOT", "PUBLIC", "PERSONAL", "TOOLS");
    private static final Set<String> OPEN_MODES = Set.of("TAB", "NEW_PAGE");
    private static final List<BuiltinMenuConfigDto> DEFAULTS = List.of(
            new BuiltinMenuConfigDto("home", "首页", "ri-home-5-line", "ROOT", 10, true, "TAB"),
            new BuiltinMenuConfigDto("projects", "项目", "ri-stack-line", "PUBLIC", 10, true, "TAB"),
            new BuiltinMenuConfigDto("ideas", "点子库", "ri-lightbulb-flash-line", "PUBLIC", 11, true, "TAB"),
            new BuiltinMenuConfigDto("skills", "Skill 市场", "ri-sparkling-2-line", "PUBLIC", 12, true, "TAB"),
            new BuiltinMenuConfigDto("chat-reviews", "聊天复盘", "ri-chat-history-line", "PUBLIC", 15, true, "TAB"),
            new BuiltinMenuConfigDto("todo", "待办", "ri-checkbox-multiple-line", "PERSONAL", 10, true, "TAB"),
            new BuiltinMenuConfigDto("workday-rhythm", "今日节奏", "ri-timer-flash-line", "PERSONAL", 12, true, "TAB"),
            new BuiltinMenuConfigDto("action-plans", "行动计划", "ri-footprint-line", "PERSONAL", 15, true, "TAB"),
            new BuiltinMenuConfigDto("calendar", "日历", "ri-calendar-2-line", "PERSONAL", 20, true, "TAB"),
            new BuiltinMenuConfigDto("weekly-report", "周报生成", "ri-file-list-3-line", "PERSONAL", 25, true, "TAB"),
            new BuiltinMenuConfigDto("life-review", "人生回顾", "ri-route-line", "PERSONAL", 27, true, "TAB"),
            new BuiltinMenuConfigDto("workspaces", "工作台", "ri-layout-grid-line", "PERSONAL", 28, true, "TAB"),
            new BuiltinMenuConfigDto("memory", "个人记忆", "ri-brain-line", "PERSONAL", 29, true, "TAB"),
            new BuiltinMenuConfigDto("knowledge", "知识库", "ri-book-2-line", "PERSONAL", 30, true, "TAB"),
            new BuiltinMenuConfigDto("english-learning", "英语训练", "ri-speak-line", "PERSONAL", 35, true, "TAB"),
            new BuiltinMenuConfigDto("vocabulary", "单词本", "ri-book-marked-line", "PERSONAL", 40, true, "TAB"),
            new BuiltinMenuConfigDto("drawings", "草图", "ri-draft-line", "PERSONAL", 50, true, "TAB"),
            new BuiltinMenuConfigDto("vault", "密码本", "ri-key-2-line", "PERSONAL", 60, true, "TAB"),
            new BuiltinMenuConfigDto("tianren", "天人一卦", "ri-compass-3-line", "PERSONAL", 70, true, "TAB"),
            new BuiltinMenuConfigDto("automation", "自动化", "ri-flashlight-line", "TOOLS", 10, true, "TAB"),
            new BuiltinMenuConfigDto("files", "文件与图床", "ri-hard-drive-2-line", "TOOLS", 20, true, "TAB"),
            new BuiltinMenuConfigDto("games", "游戏厅", "ri-gamepad-line", "TOOLS", 30, true, "TAB"));
    private static final Map<String, BuiltinMenuConfigDto> BY_ID = DEFAULTS.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(BuiltinMenuConfigDto::id, item -> item));

    private final SettingService settings;
    private final ObjectMapper json;

    public BuiltinMenuRegistryService(SettingService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** 读取注册表；缺失或损坏时返回代码默认菜单，绝不因配置错误清空导航。 */
    public BuiltinMenuRegistryDto getRegistry() {
        String raw = settings.get(SettingService.BUILTIN_MENU_REGISTRY);
        if (raw == null || raw.isBlank()) return defaults();
        try {
            BuiltinMenuRegistryDto stored = json.readValue(raw, BuiltinMenuRegistryDto.class);
            return new BuiltinMenuRegistryDto(VERSION, canonicalize(stored.menus(), false));
        } catch (RuntimeException | JsonProcessingException exception) {
            return defaults();
        }
    }

    /** 校验并整体保存所有内置菜单；不能增加、删除或改稳定 id。 */
    public BuiltinMenuRegistryDto saveRegistry(SaveBuiltinMenuRegistryRequest request) {
        BuiltinMenuRegistryDto registry = new BuiltinMenuRegistryDto(VERSION, canonicalize(request.menus(), true));
        try {
            settings.put(SettingService.BUILTIN_MENU_REGISTRY, json.writeValueAsString(registry));
            return registry;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("内置菜单注册表序列化失败", exception);
        }
    }

    private BuiltinMenuRegistryDto defaults() {
        return new BuiltinMenuRegistryDto(VERSION, DEFAULTS);
    }

    private List<BuiltinMenuConfigDto> canonicalize(List<BuiltinMenuConfigDto> menus, boolean requireAll) {
        if (menus == null || menus.size() > DEFAULTS.size() || (requireAll && menus.size() != DEFAULTS.size())) {
            throw new IllegalArgumentException("内置菜单数量不正确");
        }
        Set<String> ids = new HashSet<>();
        List<BuiltinMenuConfigDto> result = new ArrayList<>();
        for (BuiltinMenuConfigDto menu : menus) {
            if (menu == null || !BY_ID.containsKey(menu.id()) || !ids.add(menu.id())) {
                throw new IllegalArgumentException("内置菜单标识不正确");
            }
            String label = menu.label() == null ? "" : menu.label().trim();
            String icon = menu.icon() == null ? "" : menu.icon().trim();
            if (label.isEmpty() || label.length() > 24) throw new IllegalArgumentException("菜单名称不能为空且不超过 24 个字符");
            if (!ICON.matcher(icon).matches()) throw new IllegalArgumentException("菜单图标格式不正确");
            if (!GROUPS.contains(menu.group())) throw new IllegalArgumentException("菜单分组不正确");
            if (menu.order() < 0 || menu.order() > 999) throw new IllegalArgumentException("菜单排序值必须在 0 到 999 之间");
            String openMode = menu.openMode() == null ? "TAB" : menu.openMode();
            if (!OPEN_MODES.contains(openMode)) throw new IllegalArgumentException("菜单打开方式不正确");
            result.add(new BuiltinMenuConfigDto(
                    menu.id(), label, icon, menu.group(), menu.order(), menu.visible(), openMode));
        }
        // 新版本增加内置模块时，旧注册表缺少该 id 是正常升级路径：保留用户已有定制并补上新默认项。
        if (!requireAll) {
            for (BuiltinMenuConfigDto fallback : DEFAULTS) {
                if (ids.add(fallback.id())) result.add(fallback);
            }
        }
        return List.copyOf(result);
    }
}
