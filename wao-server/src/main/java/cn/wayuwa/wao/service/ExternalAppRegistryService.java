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

    private static final int REGISTRY_VERSION = 8;
    private static final int GETNOTE_INTRODUCED_VERSION = 4;
    private static final int JUMPSERVER_INTRODUCED_VERSION = 5;
    private static final int MAX_APPS = 30;
    private static final String MEDIA_AGENT_ID = "media-agent";
    private static final String DEFAULT_MEDIA_AGENT_LABEL = "自媒体 Agent";
    private static final String DEFAULT_MEDIA_AGENT_GROUP = "PUBLIC";
    private static final int DEFAULT_MEDIA_AGENT_ORDER = 20;
    private static final String GETNOTE_ID = "getnote";
    private static final String JUMPSERVER_ID = "jumpserver";
    private static final String JUMPSERVER_APP_URL = "/api/auth/jumpserver";
    private static final String JUMPSERVER_HEALTH_URL = "/api/external/jumpserver/health";
    private static final Pattern APP_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ICON = Pattern.compile("ri-[a-z0-9-]+-(?:line|fill)");
    private static final Set<String> GROUPS = Set.of("PUBLIC", "PERSONAL", "TOOLS");
    private static final Set<String> OPEN_MODES = Set.of("TAB", "NEW_PAGE");
    private static final Set<String> PLACEMENTS = Set.of("MENU", "DOCK", "TOP");
    private static final Set<String> RETIRED_INTERNAL_APP_IDS = Set.of("wao-insight", "media-agent");

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
            return new ExternalAppRegistryDto(REGISTRY_VERSION, defaultApps());
        }
        try {
            ExternalAppRegistryDto stored = json.readValue(raw, ExternalAppRegistryDto.class);
            if (stored.apps() == null || stored.version() < 1) {
                return new ExternalAppRegistryDto(REGISTRY_VERSION, defaultApps());
            }
            List<SaveExternalAppConfigRequest> requests = new ArrayList<>(stored.apps().stream()
                    .map(app -> new SaveExternalAppConfigRequest(
                            app.id(), app.label(), app.icon(), app.group(), app.order(), app.visible(),
                            app.appUrl(), app.healthUrl(), app.openMode(), app.placement()))
                    .toList());
            if (stored.version() < GETNOTE_INTRODUCED_VERSION
                    && requests.stream().noneMatch(app -> GETNOTE_ID.equals(app.id()))) {
                requests.add(getNoteRequest());
            }
            if (stored.version() < JUMPSERVER_INTRODUCED_VERSION
                    && requests.stream().noneMatch(app -> JUMPSERVER_ID.equals(app.id()))) {
                requests.add(jumpServerRequest());
            }
            if (stored.version() < 8 && requests.stream().noneMatch(app -> "weknora".equals(app.id()))) {
                requests.add(new SaveExternalAppConfigRequest("weknora", "WeKnora", "ri-book-open-line",
                        "PUBLIC", 35, true, "/weknora/", "/api/external/weknora/health", "TAB"));
            }
            return new ExternalAppRegistryDto(REGISTRY_VERSION, activeApps(canonicalize(requests)));
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException exception) {
            return new ExternalAppRegistryDto(REGISTRY_VERSION, defaultApps());
        }
    }

    /**
     * 校验并整体保存注册表。整体替换让新增、编辑、删除和排序共享一个原子配置快照。
     *
     * @param request 前端提交的完整应用列表
     * @return 清洗后实际保存的注册表
     */
    public ExternalAppRegistryDto saveRegistry(SaveExternalAppRegistryRequest request) {
        var storedPlacements = getRegistry().apps().stream()
                .collect(java.util.stream.Collectors.toMap(ExternalAppConfigDto::id, ExternalAppConfigDto::placement));
        List<SaveExternalAppConfigRequest> compatibleApps = request.apps() == null ? null : request.apps().stream()
                .map(app -> app == null || app.placement() != null ? app : new SaveExternalAppConfigRequest(
                        app.id(), app.label(), app.icon(), app.group(), app.order(), app.visible(), app.appUrl(),
                        app.healthUrl(), app.openMode(), storedPlacements.getOrDefault(app.id(), "MENU")))
                .toList();
        List<ExternalAppConfigDto> apps = activeApps(canonicalize(compatibleApps));
        return store(apps);
    }

    private static List<ExternalAppConfigDto> activeApps(List<ExternalAppConfigDto> apps) {
        return apps.stream()
                .filter(app -> !RETIRED_INTERNAL_APP_IDS.contains(app.id()))
                .toList();
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
            String placement = request.placement() == null ? "MENU" : request.placement();
            if (!PLACEMENTS.contains(placement)) {
                throw new IllegalArgumentException("展示位置不正确");
            }
            String appUrl = validateUrl(request.appUrl(), "应用入口", false);
            String healthUrl = validateUrl(request.healthUrl(), "健康检查地址", true);
            // JumpServer 是带 WAO 身份交换的系统入口，不能被历史注册表值降级成直达密码页。
            // 名称、分组、排序、显隐和打开方式仍由成员自行配置。
            if (JUMPSERVER_ID.equals(id)) {
                appUrl = JUMPSERVER_APP_URL;
                healthUrl = JUMPSERVER_HEALTH_URL;
            }
            apps.add(new ExternalAppConfigDto(
                    id,
                    label,
                    icon,
                    request.group(),
                    request.order(),
                    request.visible(),
                    appUrl,
                    healthUrl,
                    openMode,
                    placement));
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

    private List<ExternalAppConfigDto> defaultApps() {
        return activeApps(List.of(getNoteApp(), jumpServerApp(),
                new ExternalAppConfigDto("weknora", "WeKnora", "ri-book-open-line", "PUBLIC", 35,
                        true, "/weknora/", "/api/external/weknora/health", "TAB")));
    }

    private static ExternalAppConfigDto getNoteApp() {
        return new ExternalAppConfigDto(
                GETNOTE_ID,
                "得到大脑",
                "ri-video-upload-line",
                "TOOLS",
                10,
                true,
                "https://www.biji.com/",
                "",
                "NEW_PAGE");
    }

    private static SaveExternalAppConfigRequest getNoteRequest() {
        ExternalAppConfigDto app = getNoteApp();
        return new SaveExternalAppConfigRequest(
                app.id(), app.label(), app.icon(), app.group(), app.order(), app.visible(),
                app.appUrl(), app.healthUrl(), app.openMode());
    }

    private static ExternalAppConfigDto jumpServerApp() {
        return new ExternalAppConfigDto(
                JUMPSERVER_ID,
                "资源管理",
                "ri-server-line",
                "TOOLS",
                15,
                true,
                JUMPSERVER_APP_URL,
                JUMPSERVER_HEALTH_URL,
                "NEW_PAGE");
    }

    private static SaveExternalAppConfigRequest jumpServerRequest() {
        ExternalAppConfigDto app = jumpServerApp();
        return new SaveExternalAppConfigRequest(
                app.id(), app.label(), app.icon(), app.group(), app.order(), app.visible(),
                app.appUrl(), app.healthUrl(), app.openMode());
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
