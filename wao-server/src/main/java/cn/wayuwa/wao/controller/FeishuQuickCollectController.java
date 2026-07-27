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

/**
 * 群聊快速收集：配置接口 + 飞书事件订阅回调。
 *
 * <p>回调地址 {@code /api/feishu/events} 要填到飞书开放平台的「事件订阅」里，因此它必须
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

    /** 飞书事件订阅回调（URL 校验与群消息都走这里）。 */
    @PostMapping("/events")
    public Map<String, Object> events(@RequestBody(required = false) String body) {
        return service.handleEvent(body);
    }
}
