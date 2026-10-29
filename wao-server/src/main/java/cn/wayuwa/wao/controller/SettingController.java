package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FeishuWebhookConfigDto;
import cn.wayuwa.wao.dto.FeishuWebhookEndpointDto;
import cn.wayuwa.wao.dto.ExternalAppRegistryDto;
import cn.wayuwa.wao.dto.MediaAgentMenuConfigDto;
import cn.wayuwa.wao.dto.NetworkOutageWindowDto;
import cn.wayuwa.wao.dto.SaveExternalAppRegistryRequest;
import cn.wayuwa.wao.dto.SaveMediaAgentMenuConfigRequest;
import cn.wayuwa.wao.dto.SaveWebhookRequest;
import cn.wayuwa.wao.dto.SaveFeishuWebhookEndpointRequest;
import cn.wayuwa.wao.dto.SaveSiteInfoRequest;
import cn.wayuwa.wao.dto.SaveNetworkOutageWindowRequest;
import cn.wayuwa.wao.dto.SiteInfoDto;
import cn.wayuwa.wao.dto.BuiltinMenuRegistryDto;
import cn.wayuwa.wao.dto.SaveBuiltinMenuRegistryRequest;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.service.ExternalAppRegistryService;
import cn.wayuwa.wao.service.FeishuWebhookConfigService;
import cn.wayuwa.wao.service.SettingService;
import cn.wayuwa.wao.service.NetworkOutageWindowService;
import cn.wayuwa.wao.service.BuiltinMenuRegistryService;
import jakarta.validation.Valid;
import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** 系统配置接口：网站信息、断网时段、一级导航与全局飞书通知。 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService settings;
    private final ExternalAppRegistryService externalApps;
    private final BuiltinMenuRegistryService builtinMenus;
    private final FeishuWebhookNotifier notifier;
    private final FeishuWebhookConfigService webhooks;
    private final NetworkOutageWindowService networkOutageWindow;
    @Autowired private cn.wayuwa.wao.service.FeishuBotActivityService botActivity;

    @Autowired
    public SettingController(SettingService settings, ExternalAppRegistryService externalApps,
            BuiltinMenuRegistryService builtinMenus,
            FeishuWebhookNotifier notifier, FeishuWebhookConfigService webhooks,
            NetworkOutageWindowService networkOutageWindow) {
        this.settings = settings;
        this.externalApps = externalApps;
        this.builtinMenus = builtinMenus;
        this.notifier = notifier;
        this.webhooks = webhooks;
        this.networkOutageWindow = networkOutageWindow;
    }

    /** 兼容不关心内置菜单注册表的既有单元测试与嵌入调用。 */
    public SettingController(SettingService settings, ExternalAppRegistryService externalApps,
            FeishuWebhookNotifier notifier) {
        this(settings, externalApps, new BuiltinMenuRegistryService(settings, new ObjectMapper()), notifier, null,
                new NetworkOutageWindowService(settings));
    }

    /** 返回自动任务统一遵守的每日断网时段。 */
    @GetMapping("/network-outage-window")
    public ApiResponse<NetworkOutageWindowDto> networkOutageWindow() {
        return ApiResponse.ok(networkOutageWindow.get());
    }

    /** 保存断网时段；支持跨午夜，开始时间包含、结束时间不包含。 */
    @PutMapping("/network-outage-window")
    public ApiResponse<NetworkOutageWindowDto> saveNetworkOutageWindow(
            @Valid @RequestBody SaveNetworkOutageWindowRequest request) {
        return ApiResponse.ok(networkOutageWindow.save(request));
    }

    @GetMapping("/feishu-webhook")
    public ApiResponse<FeishuWebhookConfigDto> webhook() {
        return ApiResponse.ok(webhookConfig());
    }

    /** 通用飞书自定义机器人列表；Webhook 地址始终脱敏。 */
    @GetMapping("/webhooks")
    public ApiResponse<List<FeishuWebhookEndpointDto>> webhooks() {
        return ApiResponse.ok(webhooks.list());
    }

    /** 新增一项可供系统其它功能复用的机器人配置。 */
    @PostMapping("/webhooks")
    public ApiResponse<FeishuWebhookEndpointDto> createWebhook(
            @Valid @RequestBody SaveFeishuWebhookEndpointRequest request) {
        return ApiResponse.ok(webhooks.create(request));
    }

    /** 编辑名称、用途、开关、默认路由，或按需替换 Webhook 地址。 */
    @PutMapping("/webhooks/{id}")
    public ApiResponse<FeishuWebhookEndpointDto> updateWebhook(@PathVariable String id,
            @Valid @RequestBody SaveFeishuWebhookEndpointRequest request) {
        return ApiResponse.ok(webhooks.update(id, request));
    }

    /** 删除机器人配置；删除默认项时服务会自动提升一项剩余配置。 */
    @DeleteMapping("/webhooks/{id}")
    public ApiResponse<Void> deleteWebhook(@PathVariable String id,
            @RequestParam(required = false) Long revision,
            @RequestParam(defaultValue = "false") boolean impactConfirmed) {
        webhooks.delete(id, revision, impactConfirmed);
        return ApiResponse.ok(null);
    }

    /** 用指定配置发送测试卡片，测试不受其启用开关影响。 */
    @PostMapping("/webhooks/{id}/test")
    public ApiResponse<Void> testWebhook(@PathVariable String id) {
        notifier.sendTest(id, FeishuWebhookNotifier.Card.simple(
                "WAO 测试通知", "green", "机器人配置可用，之后可在 WAO 的通知功能中复用。"));
        return ApiResponse.ok(null);
    }

    /** 查询真实引用和最近结果；发送测试成功不能冒充消息收集已验证。 */
    @GetMapping("/webhooks/{id}/activity")
    public ApiResponse<cn.wayuwa.wao.dto.FeishuBotActivityDto> webhookActivity(@PathVariable String id) {
        return ApiResponse.ok(botActivity.activity(id));
    }

    @GetMapping("/site-info")
    public ApiResponse<SiteInfoDto> siteInfo() {
        return ApiResponse.ok(siteInfo(settings));
    }

    @PutMapping("/site-info")
    public ApiResponse<SiteInfoDto> saveSiteInfo(@Valid @RequestBody SaveSiteInfoRequest request) {
        validatePublicUrl(request.icpUrl());
        settings.put(SettingService.SITE_NAME, request.siteName().trim());
        settings.put(SettingService.SITE_DESCRIPTION, trim(request.description()));
        settings.put(SettingService.SITE_ICP_NUMBER, request.icpNumber().trim());
        settings.put(SettingService.SITE_ICP_URL, request.icpUrl().trim());
        return ApiResponse.ok(siteInfo(settings));
    }

    @GetMapping("/navigation/external-apps")
    public ApiResponse<ExternalAppRegistryDto> externalAppRegistry() {
        return ApiResponse.ok(externalApps.getRegistry());
    }

    @PutMapping("/navigation/external-apps")
    public ApiResponse<ExternalAppRegistryDto> saveExternalAppRegistry(
            @Valid @RequestBody SaveExternalAppRegistryRequest request) {
        return ApiResponse.ok(externalApps.saveRegistry(request));
    }

    @GetMapping("/navigation/builtin-menus")
    public ApiResponse<BuiltinMenuRegistryDto> builtinMenuRegistry() {
        return ApiResponse.ok(builtinMenus.getRegistry());
    }

    @PutMapping("/navigation/builtin-menus")
    public ApiResponse<BuiltinMenuRegistryDto> saveBuiltinMenuRegistry(
            @Valid @RequestBody SaveBuiltinMenuRegistryRequest request) {
        throw new cn.wayuwa.wao.common.BadRequestException("菜单由开发人员统一维护，不再支持在线配置");
    }

    @PutMapping("/feishu-webhook")
    public ApiResponse<FeishuWebhookConfigDto> saveWebhook(@RequestBody SaveWebhookRequest request) {
        if (webhooks == null) {
            settings.put(SettingService.FEISHU_WEBHOOK_URL, request.url() == null ? null : request.url().trim());
            if (request.enabled() != null) {
                settings.put(SettingService.FEISHU_WEBHOOK_ENABLED, String.valueOf(request.enabled()));
            }
        } else {
            webhooks.saveLegacyDefault(request.url(), request.enabled());
        }
        return ApiResponse.ok(webhookConfig());
    }

    /** 用当前配置发一张测试卡片，验证地址确实通。 */
    @PostMapping("/feishu-webhook/test")
    public ApiResponse<Void> testWebhook() {
        notifier.send(notifier.resolvedUrl(), FeishuWebhookNotifier.Card.simple(
                "WAO 测试通知", "green", "webhook 配置成功，之后的系统通知都会以卡片形式发到这个群。"));
        return ApiResponse.ok(null);
    }

    private FeishuWebhookConfigDto webhookConfig() {
        if (webhooks != null) {
            var configured = webhooks.defaultConfig();
            if (configured.isPresent()) {
                FeishuWebhookEndpointDto endpoint = configured.get();
                return new FeishuWebhookConfigDto(endpoint.urlMask(), endpoint.urlSet(), endpoint.enabled());
            }
            if (webhooks.registryInitialized()) return new FeishuWebhookConfigDto("", false, false);
        }
        String url = settings.get(SettingService.FEISHU_WEBHOOK_URL);
        return new FeishuWebhookConfigDto(SettingService.mask(url), url != null && !url.isBlank(),
                settings.getBoolean(SettingService.FEISHU_WEBHOOK_ENABLED));
    }

    public static SiteInfoDto siteInfo(SettingService settings) {
        return new SiteInfoDto(
                settings.get(SettingService.SITE_NAME, "WAO Life OS"),
                settings.get(SettingService.SITE_DESCRIPTION, "个人事务与 AI 协作系统"),
                settings.get(SettingService.SITE_ICP_NUMBER, "豫ICP备2024105476号-1"),
                settings.get(SettingService.SITE_ICP_URL, "https://beian.miit.gov.cn"));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validatePublicUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("备案查询链接格式不正确");
        }
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("备案查询链接必须是有效的 HTTP 或 HTTPS 地址");
        }
    }
}
