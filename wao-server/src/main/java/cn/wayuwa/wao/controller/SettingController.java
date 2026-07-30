package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FeishuWebhookConfigDto;
import cn.wayuwa.wao.dto.ExternalAppRegistryDto;
import cn.wayuwa.wao.dto.MediaAgentMenuConfigDto;
import cn.wayuwa.wao.dto.SaveExternalAppRegistryRequest;
import cn.wayuwa.wao.dto.SaveMediaAgentMenuConfigRequest;
import cn.wayuwa.wao.dto.SaveWebhookRequest;
import cn.wayuwa.wao.dto.SaveSiteInfoRequest;
import cn.wayuwa.wao.dto.SiteInfoDto;
import cn.wayuwa.wao.dto.BuiltinMenuRegistryDto;
import cn.wayuwa.wao.dto.SaveBuiltinMenuRegistryRequest;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.service.ExternalAppRegistryService;
import cn.wayuwa.wao.service.SettingService;
import cn.wayuwa.wao.service.BuiltinMenuRegistryService;
import jakarta.validation.Valid;
import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统配置接口：网站信息、一级导航与全局飞书通知。 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService settings;
    private final ExternalAppRegistryService externalApps;
    private final BuiltinMenuRegistryService builtinMenus;
    private final FeishuWebhookNotifier notifier;

    @Autowired
    public SettingController(SettingService settings, ExternalAppRegistryService externalApps,
            BuiltinMenuRegistryService builtinMenus,
            FeishuWebhookNotifier notifier) {
        this.settings = settings;
        this.externalApps = externalApps;
        this.builtinMenus = builtinMenus;
        this.notifier = notifier;
    }

    /** 兼容不关心内置菜单注册表的既有单元测试与嵌入调用。 */
    public SettingController(SettingService settings, ExternalAppRegistryService externalApps,
            FeishuWebhookNotifier notifier) {
        this(settings, externalApps, new BuiltinMenuRegistryService(settings, new ObjectMapper()), notifier);
    }

    @GetMapping("/feishu-webhook")
    public ApiResponse<FeishuWebhookConfigDto> webhook() {
        return ApiResponse.ok(webhookConfig());
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

    @GetMapping("/navigation/media-agent")
    public ApiResponse<MediaAgentMenuConfigDto> mediaAgentMenu() {
        return ApiResponse.ok(externalApps.getLegacyMediaAgentMenu());
    }

    @PutMapping("/navigation/media-agent")
    public ApiResponse<MediaAgentMenuConfigDto> saveMediaAgentMenu(
            @Valid @RequestBody SaveMediaAgentMenuConfigRequest request) {
        return ApiResponse.ok(externalApps.saveLegacyMediaAgentMenu(request));
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
        return ApiResponse.ok(builtinMenus.saveRegistry(request));
    }

    @PutMapping("/feishu-webhook")
    public ApiResponse<FeishuWebhookConfigDto> saveWebhook(@RequestBody SaveWebhookRequest request) {
        settings.put(SettingService.FEISHU_WEBHOOK_URL, request.url() == null ? null : request.url().trim());
        if (request.enabled() != null) {
            settings.put(SettingService.FEISHU_WEBHOOK_ENABLED, String.valueOf(request.enabled()));
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
