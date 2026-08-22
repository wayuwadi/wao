package cn.wayuwa.wao.service;

import cn.wayuwa.wao.dto.ExternalAppConfigDto;
import cn.wayuwa.wao.dto.ExternalAppRegistryDto;
import cn.wayuwa.wao.dto.MediaAgentMenuConfigDto;
import cn.wayuwa.wao.dto.SaveExternalAppConfigRequest;
import cn.wayuwa.wao.dto.SaveExternalAppRegistryRequest;
import cn.wayuwa.wao.dto.SaveMediaAgentMenuConfigRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 外部应用菜单注册表：用一份版本化 JSON 驱动 WAO 导航和通用嵌入页。
 *
 * <p>注册表首次启用前，仍从旧的 {@code navigation.media-agent.*} 四个键读取，确保已经上线的
 * Agent 菜单配置无损过渡。保存注册表后以新键为准，旧接口则作为滚动发布兼容层继续可用。
 * 已取消且没有真实后端的内部 Insight 会在读取和保存时过滤，避免旧配置恢复伪入口。
 */
@Service
public class ExternalAppRegistryService {

    private static final int REGISTRY_VERSION = 3;
    private static final int MAX_APPS = 30;
    private static final String MEDIA_AGENT_ID = "media-agent";
    private static final String DEFAULT_MEDIA_AGENT_LABEL = "自媒体 Agent";
    private static final String DEFAULT_MEDIA_AGENT_GROUP = "PUBLIC";
    private static final int DEFAULT_MEDIA_AGENT_ORDER = 20;
    private static final Pattern APP_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ICON = Pattern.compile("ri-[a-z0-9-]+-(?:line|fill)");
    private static final Set<String> GROUPS = Set.of("PUBLIC", "PERSONAL", "TOOLS");
    private static final Set<String> OPEN_MODES = Set.of("TAB", "NEW_PAGE");
    private static final Set<String> RETIRED_INTERNAL_APP_IDS = Set.of("wao-insight");

    private final SettingService settings;
    private final ObjectMapper json;

    public ExternalAppRegistryService(SettingService settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /**
     * 读取完整注册表；新键尚未保存或内容损坏时回落到旧 Agent 配置，并过滤已退役内部应用。
     *
     * @return 可直接回给前端的注册表
     */
    public ExternalAppRegistryDto getRegistry() {
        String raw = settings.get(SettingService.EXTERNAL_APP_REGISTRY);
        if (raw == null || raw.isBlank()) {
            return new ExternalAppRegistryDto(REGISTRY_VERSION, List.of(legacyMediaAgent()));
        }
        try {
            ExternalAppRegistryDto stored = json.readValue(raw, ExternalAppRegistryDto.class);
            if (stored.apps() == null || stored.version() < 1) {
                return new ExternalAppRegistryDto(REGISTRY_VERSION, List.of(legacyMediaAgent()));
            }
            List<SaveExternalAppConfigRequest> requests = stored.apps().stream()
                    .map(app -> new SaveExternalAppConfigRequest(
                            app.id(), app.label(), app.icon(), app.group(), app.order(), app.visible(),
                            app.appUrl(), app.healthUrl(), app.openMode()))
                    .toList();
            return new ExternalAppRegistryDto(REGISTRY_VERSION, activeApps(canonicalize(requests)));
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            return new ExternalAppRegistryDto(REGISTRY_VERSION, List.of(legacyMediaAgent()));
        }
    }

    /**
     * 校验并整体保存注册表。整体替换让新增、编辑、删除和排序共享一个原子配置快照。
     *
     * @param request 前端提交的完整应用列表
     * @return 清洗后实际保存的注册表
     */
    public ExternalAppRegistryDto saveRegistry(SaveExternalAppRegistryRequest request) {
        List<ExternalAppConfigDto> apps = activeApps(canonicalize(request.apps()));
        return store(apps);
    }

    private static List<ExternalAppConfigDto> activeApps(List<ExternalAppConfigDto> apps) {
        return apps.stream()
                .filter(app -> !RETIRED_INTERNAL_APP_IDS.contains(app.id()))
                .toList();
    }

    /**
     * 兼容旧前端读取单个 Agent 菜单配置。
     *
     * @return media-agent 当前的菜单字段；已删除时回退旧键默认值
     */
    public MediaAgentMenuConfigDto getLegacyMediaAgentMenu() {
        ExternalAppConfigDto app = getRegistry().apps().stream()
                .filter(item -> MEDIA_AGENT_ID.equals(item.id()))
                .findFirst()
                .orElseGet(this::legacyMediaAgent);
        return new MediaAgentMenuConfigDto(app.label(), app.group(), app.order(), app.visible());
    }

    /**
     * 兼容旧前端保存 Agent 菜单字段，同时保留新注册表中的图标和应用地址。
     *
     * @param request 旧版四字段菜单表单
     * @return 保存后的旧版响应
     */
    public MediaAgentMenuConfigDto saveLegacyMediaAgentMenu(SaveMediaAgentMenuConfigRequest request) {
        List<ExternalAppConfigDto> apps = new ArrayList<>(getRegistry().apps());
        int index = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (MEDIA_AGENT_ID.equals(apps.get(i).id())) {
                index = i;
                break;
            }
        }
        ExternalAppConfigDto current = index >= 0 ? apps.get(index) : legacyMediaAgent();
        ExternalAppConfigDto updated = new ExternalAppConfigDto(
                MEDIA_AGENT_ID,
                request.label().trim(),
                current.icon(),
                request.group(),
                request.order(),
                request.visible(),
                current.appUrl(),
                current.healthUrl(),
                current.openMode());
        if (index >= 0) {
            apps.set(index, updated);
        } else {
            apps.add(updated);
        }
        store(apps);
        return new MediaAgentMenuConfigDto(updated.label(), updated.group(), updated.order(), updated.visible());
    }

    private List<ExternalAppConfigDto> canonicalize(List<SaveExternalAppConfigRequest> requests) {
        if (requests == null || requests.size() > MAX_APPS) {
            throw new IllegalArgumentException("外部应用不能超过 30 个");
        }
        Set<String> ids = new HashSet<>();
        List<ExternalAppConfigDto> apps = new ArrayList<>();
        for (SaveExternalAppConfigRequest request : requests) {
            String id = request.id().trim();
            if (!APP_ID.matcher(id).matches() || id.length() > 40) {
                throw new IllegalArgumentException("应用标识格式不正确：" + id);
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("应用标识不能重复：" + id);
            }
            String label = request.label().trim();
            String icon = request.icon().trim();
            if (label.isEmpty() || label.length() > 24) {
                throw new IllegalArgumentException("菜单名称不能为空且不能超过 24 个字符");
            }
            if (!ICON.matcher(icon).matches() || icon.length() > 64) {
                throw new IllegalArgumentException("菜单图标格式不正确");
            }
            if (!GROUPS.contains(request.group())) {
                throw new IllegalArgumentException("所属分组不正确");
            }
            if (request.order() == null || request.order() < 0 || request.order() > 999) {
                throw new IllegalArgumentException("排序值必须在 0 到 999 之间");
            }
            if (request.visible() == null) {
                throw new IllegalArgumentException("请选择是否显示");
            }
            String openMode = request.openMode() == null ? "TAB" : request.openMode();
            if (!OPEN_MODES.contains(openMode)) {
                throw new IllegalArgumentException("打开方式不正确");
            }
            apps.add(new ExternalAppConfigDto(
                    id,
                    label,
                    icon,
                    request.group(),
                    request.order(),
                    request.visible(),
                    validateUrl(request.appUrl(), "应用入口", false),
                    validateUrl(request.healthUrl(), "健康检查地址", true),
                    openMode));
        }
        return List.copyOf(apps);
    }

    private ExternalAppRegistryDto store(List<ExternalAppConfigDto> apps) {
        if (apps.size() > MAX_APPS) {
            throw new IllegalArgumentException("外部应用不能超过 30 个");
        }
        ExternalAppRegistryDto registry = new ExternalAppRegistryDto(REGISTRY_VERSION, List.copyOf(apps));
        try {
            settings.put(SettingService.EXTERNAL_APP_REGISTRY, json.writeValueAsString(registry));
            return registry;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("外部应用注册表序列化失败", exception);
        }
    }

    private ExternalAppConfigDto legacyMediaAgent() {
        String group = settings.get(SettingService.MEDIA_AGENT_MENU_GROUP, DEFAULT_MEDIA_AGENT_GROUP);
        if (!("PUBLIC".equals(group) || "PERSONAL".equals(group) || "TOOLS".equals(group))) {
            group = DEFAULT_MEDIA_AGENT_GROUP;
        }
        return new ExternalAppConfigDto(
                MEDIA_AGENT_ID,
                settings.get(SettingService.MEDIA_AGENT_MENU_LABEL, DEFAULT_MEDIA_AGENT_LABEL),
                "ri-sparkling-2-line",
                group,
                parseOrder(settings.get(
                        SettingService.MEDIA_AGENT_MENU_ORDER, String.valueOf(DEFAULT_MEDIA_AGENT_ORDER))),
                !"false".equalsIgnoreCase(settings.get(SettingService.MEDIA_AGENT_MENU_VISIBLE, "true")),
                "/agent/",
                "/api/agent/health",
                "TAB");
    }

    private static int parseOrder(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0 && parsed <= 999 ? parsed : DEFAULT_MEDIA_AGENT_ORDER;
        } catch (NumberFormatException ignored) {
            return DEFAULT_MEDIA_AGENT_ORDER;
        }
    }

    private static String validateUrl(String raw, String field, boolean optional) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() && optional) {
            return "";
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + "格式不正确");
        }
        boolean relative = value.startsWith("/") && !value.startsWith("//")
                && uri.getScheme() == null && uri.getHost() == null;
        boolean secureAbsolute = "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && uri.getUserInfo() == null;
        if (!relative && !secureAbsolute) {
            throw new IllegalArgumentException(field + "必须是站内路径或 HTTPS 地址");
        }
        return value;
    }
}
