package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FeishuWebhookConfigDto;
import cn.wayuwa.wao.dto.SaveWebhookRequest;
import cn.wayuwa.wao.feishu.FeishuWebhookNotifier;
import cn.wayuwa.wao.service.SettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统配置接口：目前是全局唯一一个的飞书通知 webhook。 */
@RestController
@RequestMapping("/api/settings")
public class SettingController {

    private final SettingService settings;
    private final FeishuWebhookNotifier notifier;

    public SettingController(SettingService settings, FeishuWebhookNotifier notifier) {
        this.settings = settings;
        this.notifier = notifier;
    }

    @GetMapping("/feishu-webhook")
    public ApiResponse<FeishuWebhookConfigDto> webhook() {
        return ApiResponse.ok(webhookConfig());
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
}
