package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.FeishuTextPolishConfigDto;
import cn.wayuwa.wao.dto.SaveFeishuTextPolishConfigRequest;
import cn.wayuwa.wao.service.FeishuTextPolishConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 群聊润色机器人配置接口。 */
@RestController
@RequestMapping("/api/feishu/text-polish/config")
public class FeishuTextPolishController {
    private final FeishuTextPolishConfigService service;

    public FeishuTextPolishController(FeishuTextPolishConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<FeishuTextPolishConfigDto> config() {
        return ApiResponse.ok(service.config());
    }

    @PutMapping
    public ApiResponse<FeishuTextPolishConfigDto> save(
            @RequestBody SaveFeishuTextPolishConfigRequest request) {
        return ApiResponse.ok(service.save(request));
    }
}
