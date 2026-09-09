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

    private static final int VERSION = 42;
    private static final Pattern ICON = Pattern.compile("ri-[a-z0-9-]+-(?:line|fill)");
    private static final Set<String> GROUPS = Set.of("ROOT", "PUBLIC", "PERSONAL", "TOOLS");
    private static final Set<String> OPEN_MODES = Set.of("TAB", "NEW_PAGE");
    private static final Set<String> PLACEMENTS = Set.of("MENU", "DOCK", "TOP");
    /** 导航中心上线后，旧版默认可见但非每日使用的入口收进全量目录；用户改名、换组或调序的定制不覆盖。 */
    private static final Set<String> COMPACT_ON_UPGRADE = Set.of(
            "skills", "skill-connections", "action-plans", "weekly-report", "life-review", "workspaces",
            "english-learning", "vocabulary", "drawings", "vault", "tianren", "dashboard", "automation",
            "files", "games", "request-monitoring");
    /** 已融合进其它模块的旧入口；读取旧注册表时忽略，不能因此丢掉用户的其它菜单定制。 */
    private static final Set<String> RETIRED_IDS = Set.of("ideas", "chat", "chat-reviews", "system-reports");
    private static final List<BuiltinMenuConfigDto> DEFAULTS = List.of(
            new BuiltinMenuConfigDto("chatgpt", "ChatGPT对话", "ri-openai-line", "ROOT", 13, true, "TAB"),
            new BuiltinMenuConfigDto("ai-sessions", "AI 会话", "ri-chat-history-line", "ROOT", 12, true, "TAB"),
            new BuiltinMenuConfigDto("home", "首页", "ri-home-5-line", "ROOT", 10, true, "TAB"),
            new BuiltinMenuConfigDto("notes", "小记", "ri-sticky-note-line", "ROOT", 15, true, "TAB"),
            new BuiltinMenuConfigDto("navigation", "工作台", "ri-compass-discover-line", "ROOT", 20, true, "TAB"),
            new BuiltinMenuConfigDto("projects", "项目", "ri-stack-line", "PUBLIC", 10, true, "TAB"),
            new BuiltinMenuConfigDto("updates", "更新日记", "ri-history-line", "PUBLIC", 11, true, "TAB"),
            new BuiltinMenuConfigDto("skills", "WAO-HUB", "ri-sparkling-2-line", "PUBLIC", 12, false, "TAB"),
            new BuiltinMenuConfigDto("skill-connections", "Skill 接入", "ri-apps-2-line", "PUBLIC", 13, false, "TAB"),
            new BuiltinMenuConfigDto("todo", "待办", "ri-checkbox-multiple-line", "PERSONAL", 10, true, "TAB"),
            new BuiltinMenuConfigDto("workday-rhythm", "今日节奏", "ri-timer-flash-line", "PERSONAL", 12, true, "TAB"),
            new BuiltinMenuConfigDto("action-plans", "行动计划", "ri-footprint-line", "PERSONAL", 15, false, "TAB"),
            new BuiltinMenuConfigDto("decisions", "决策管理", "ri-route-line", "PERSONAL", 16, true, "TAB"),
            new BuiltinMenuConfigDto("appearance", "电子衣库", "ri-shirt-line", "PERSONAL", 17, true, "TAB"),
            new BuiltinMenuConfigDto("food", "饮食管理", "ri-restaurant-2-line", "PERSONAL", 18, true, "TAB"),
            new BuiltinMenuConfigDto("calendar", "日历", "ri-calendar-2-line", "PERSONAL", 20, true, "TAB"),
            new BuiltinMenuConfigDto("weekly-report", "周报生成", "ri-file-list-3-line", "PERSONAL", 25, false, "TAB"),
            new BuiltinMenuConfigDto("life-review", "人生回顾", "ri-route-line", "PERSONAL", 27, false, "TAB"),
            new BuiltinMenuConfigDto("workspaces", "工作台", "ri-layout-grid-line", "PERSONAL", 28, false, "TAB"),
            new BuiltinMenuConfigDto("memory", "个人记忆", "ri-brain-line", "PERSONAL", 29, true, "TAB"),
            new BuiltinMenuConfigDto("knowledge", "知识库", "ri-book-2-line", "PERSONAL", 30, true, "TAB"),
            new BuiltinMenuConfigDto("english-learning", "英语训练", "ri-speak-line", "PERSONAL", 35, false, "TAB"),
            new BuiltinMenuConfigDto("vocabulary", "单词本", "ri-book-marked-line", "PERSONAL", 40, false, "TAB"),
            new BuiltinMenuConfigDto("drawings", "草图", "ri-draft-line", "PERSONAL", 50, false, "TAB"),
            new BuiltinMenuConfigDto("vault", "密码本", "ri-key-2-line", "PERSONAL", 60, false, "TAB"),
            new BuiltinMenuConfigDto("tianren", "天人一卦", "ri-compass-3-line", "PERSONAL", 70, false, "TAB"),
            new BuiltinMenuConfigDto("data-center", "数据中心", "ri-database-2-line", "TOOLS", 3, true, "TAB"),
            new BuiltinMenuConfigDto("text-polish", "润色", "ri-magic-line", "TOOLS", 4, true, "TAB"),
            new BuiltinMenuConfigDto("dashboard", "使用统计", "ri-bar-chart-box-line", "TOOLS", 5, false, "TAB"),
            new BuiltinMenuConfigDto("automation", "自动化", "ri-flashlight-line", "TOOLS", 10, false, "TAB"),
            new BuiltinMenuConfigDto("crawler-capabilities", "爬虫能力", "ri-code-box-line", "TOOLS", 15, true, "TAB"),
            new BuiltinMenuConfigDto("account-resources", "账号资源库", "ri-contacts-book-2-line", "TOOLS", 16, true, "TAB"),
            new BuiltinMenuConfigDto("files", "文件与图床", "ri-hard-drive-2-line", "TOOLS", 20, false, "TAB"),
            new BuiltinMenuConfigDto("games", "游戏厅", "ri-gamepad-line", "TOOLS", 30, false, "TAB"),
            new BuiltinMenuConfigDto("infrastructure", "基础设施", "ri-server-line", "TOOLS", 34, true, "TAB"),
            new BuiltinMenuConfigDto("devices", "终端设备", "ri-webcam-line", "TOOLS", 35, true, "TAB"),
            new BuiltinMenuConfigDto("request-monitoring", "请求监控", "ri-pulse-line", "TOOLS", 40, false, "TAB"));
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
            List<BuiltinMenuConfigDto> menus = canonicalize(stored.menus(), false);
            if (stored.version() < 25) menus = applyWorkbenchDefault(menus);
            if (stored.version() < 37) menus = applyCompactDefaults(menus);
            if (stored.version() < 36) menus = applyElectronicWardrobeDefault(menus);
            if (stored.version() < 42) menus = menus.stream().map(menu ->
                    "skills".equals(menu.id()) && "Skill 市场".equals(menu.label())
                    ? new BuiltinMenuConfigDto(menu.id(), "WAO-HUB", menu.icon(), menu.group(), menu.order(), menu.visible(), menu.openMode(), menu.placement()) : menu).toList();
            return new BuiltinMenuRegistryDto(VERSION, menus);
        } catch (RuntimeException | JsonProcessingException exception) {
            return defaults();
        }
    }

    /** 校验并整体保存所有内置菜单；不能增加、删除或改稳定 id。 */
    public BuiltinMenuRegistryDto saveRegistry(SaveBuiltinMenuRegistryRequest request) {
        Map<String, String> storedPlacements = getRegistry().menus().stream()
                .collect(java.util.stream.Collectors.toMap(BuiltinMenuConfigDto::id, BuiltinMenuConfigDto::placement));
        List<BuiltinMenuConfigDto> compatibleMenus = request.menus() == null ? null : request.menus().stream()
                .map(menu -> menu == null || menu.placement() != null ? menu : new BuiltinMenuConfigDto(
                        menu.id(), menu.label(), menu.icon(), menu.group(), menu.order(), menu.visible(),
                        menu.openMode(), storedPlacements.getOrDefault(menu.id(), "MENU")))
                .toList();
        BuiltinMenuRegistryDto registry = new BuiltinMenuRegistryDto(VERSION, canonicalize(compatibleMenus, true));
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
        if (menus == null || (requireAll && menus.size() != DEFAULTS.size())) {
            throw new IllegalArgumentException("内置菜单数量不正确");
        }
        Set<String> ids = new HashSet<>();
        List<BuiltinMenuConfigDto> result = new ArrayList<>();
        for (BuiltinMenuConfigDto menu : menus) {
            if (!requireAll && menu != null && RETIRED_IDS.contains(menu.id())) continue;
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
            String placement = menu.placement() == null ? "MENU" : menu.placement();
            if (!PLACEMENTS.contains(placement)) throw new IllegalArgumentException("菜单展示位置不正确");
            result.add(new BuiltinMenuConfigDto(
                    menu.id(), label, icon, menu.group(), menu.order(), menu.visible(), openMode, placement));
        }
        // 新版本增加内置模块时，旧注册表缺少该 id 是正常升级路径：保留用户已有定制并补上新默认项。
        if (!requireAll) {
            for (BuiltinMenuConfigDto fallback : DEFAULTS) {
                if (ids.add(fallback.id())) result.add(fallback);
            }
        }
        return List.copyOf(result);
    }

    private List<BuiltinMenuConfigDto> applyCompactDefaults(List<BuiltinMenuConfigDto> menus) {
        return menus.stream().map(menu -> {
            BuiltinMenuConfigDto currentDefault = BY_ID.get(menu.id());
            if (!COMPACT_ON_UPGRADE.contains(menu.id()) || !menu.visible() || currentDefault == null
                    || !menu.label().equals(currentDefault.label()) || !menu.icon().equals(currentDefault.icon())
                    || !menu.group().equals(currentDefault.group()) || menu.order() != currentDefault.order()
                    || !menu.openMode().equals(currentDefault.openMode())) {
                return menu;
            }
            return new BuiltinMenuConfigDto(menu.id(), menu.label(), menu.icon(), menu.group(), menu.order(),
                    false, menu.openMode(), menu.placement());
        }).toList();
    }

    /** V25 只把未经定制的旧“导航”改名为“工作台”；成员主动修改过的名称与元数据保持不变。 */
    private List<BuiltinMenuConfigDto> applyWorkbenchDefault(List<BuiltinMenuConfigDto> menus) {
        return menus.stream().map(menu -> {
            if (!"navigation".equals(menu.id()) || !"导航".equals(menu.label())
                    || !"ri-compass-discover-line".equals(menu.icon()) || !"ROOT".equals(menu.group())
                    || menu.order() != 20 || !menu.visible() || !"TAB".equals(menu.openMode())) {
                return menu;
            }
            return BY_ID.get("navigation");
        }).toList();
    }

    /** V36 只把未经定制的旧“形象管理”改名为“电子衣库”，继续保留稳定 id 和用户自定义入口。 */
    private List<BuiltinMenuConfigDto> applyElectronicWardrobeDefault(List<BuiltinMenuConfigDto> menus) {
        return menus.stream().map(menu -> {
            if (!"appearance".equals(menu.id()) || !"形象管理".equals(menu.label())
                    || !"ri-shirt-line".equals(menu.icon()) || !"PERSONAL".equals(menu.group())
                    || menu.order() != 17 || !menu.visible() || !"TAB".equals(menu.openMode())) {
                return menu;
            }
            return BY_ID.get("appearance");
        }).toList();
    }
}
