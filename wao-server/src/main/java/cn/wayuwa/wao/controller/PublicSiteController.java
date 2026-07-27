package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.SiteInfoDto;
import cn.wayuwa.wao.service.SettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 无需登录的网站公开信息，供登录页、分享页和全局页脚使用。 */
@RestController
@RequestMapping("/api/public")
public class PublicSiteController {

    private final SettingService settings;

    public PublicSiteController(SettingService settings) {
        this.settings = settings;
    }

    @GetMapping("/site-info")
    public ApiResponse<SiteInfoDto> siteInfo() {
        return ApiResponse.ok(SettingController.siteInfo(settings));
    }
}
