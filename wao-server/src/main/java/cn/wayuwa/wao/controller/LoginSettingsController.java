package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.auth.WechatOfficialAccountConfigService;
import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SaveWechatOfficialLoginConfigRequest;
import cn.wayuwa.wao.dto.WechatOfficialLoginConfigDto;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统登录方式配置接口。 */
@RestController
@RequestMapping("/api/settings/login")
public class LoginSettingsController {
    private final WechatOfficialAccountConfigService wechatOfficial;

    public LoginSettingsController(WechatOfficialAccountConfigService wechatOfficial) {
        this.wechatOfficial = wechatOfficial;
    }

    /** 读取微信公众号登录的启用状态与脱敏凭据。 */
    @GetMapping("/wechat-official")
    public ApiResponse<WechatOfficialLoginConfigDto> wechatOfficial() {
        return ApiResponse.ok(wechatOfficial.get());
    }

    /** 保存微信公众号登录配置并立即用于后续登录会话。 */
    @PutMapping("/wechat-official")
    public ApiResponse<WechatOfficialLoginConfigDto> saveWechatOfficial(
            @Valid @RequestBody SaveWechatOfficialLoginConfigRequest request) {
        return ApiResponse.ok(wechatOfficial.save(request));
    }
}
