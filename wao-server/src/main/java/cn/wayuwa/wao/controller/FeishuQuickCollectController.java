package cn.wayuwa.wao.controller;

import java.util.Map;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FeishuQuickCollectConfigDto;
import cn.wayuwa.wao.dto.SaveQuickCollectConfigRequest;
import cn.wayuwa.wao.service.FeishuQuickCollectService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * 群聊快速收集：配置接口 + 飞书事件订阅及卡片交互回调。
 *
 * <p>地址 {@code /api/feishu/events} 要同时填到飞书开放平台的「事件订阅」和
 * 「事件与回调 → 回调配置 → 卡片回传交互」里，因此它必须
 * 免登录（见 {@code AuthInterceptor}）；请求真实性由 Verification Token / Encrypt Key 保证。
 * 它也不返回 {@code ApiResponse} 包装 —— 飞书按自己的协议读 {@code challenge} 字段。
 */
@RestController
@RequestMapping("/api/feishu")
public class FeishuQuickCollectController {

    private final FeishuQuickCollectService service;

    public FeishuQuickCollectController(FeishuQuickCollectService service) {
        this.service = service;
    }

    @GetMapping("/quick-collect/config")
    public ApiResponse<FeishuQuickCollectConfigDto> config() {
        return ApiResponse.ok(service.config());
    }

    @PutMapping("/quick-collect/config")
    public ApiResponse<FeishuQuickCollectConfigDto> saveConfig(@RequestBody SaveQuickCollectConfigRequest request) {
        return ApiResponse.ok(service.saveConfig(request));
    }

    /** 飞书事件订阅与卡片交互回调（URL 校验、群消息和卡片按钮都走这里）。 */
    @PostMapping("/events")
    public Map<String, Object> events(@RequestBody(required = false) String body) {
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        return service.handleEvent(body, baseUrl);
    }
}
