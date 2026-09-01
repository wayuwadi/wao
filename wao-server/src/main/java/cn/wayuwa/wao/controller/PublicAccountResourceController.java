package cn.wayuwa.wao.controller;

import cn.wayuwa.wao.common.ApiResponse;
import cn.wayuwa.wao.dto.InboundVerificationMessageRequest;
import cn.wayuwa.wao.dto.LatestVerificationCodeDto;
import cn.wayuwa.wao.dto.VerificationIngestResultDto;
import cn.wayuwa.wao.service.AccountResourceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 手机短信转发器和爬虫使用的令牌鉴权接口；不依赖浏览器 WAO 会话。 */
@RestController
@RequestMapping("/api/public/account-resources")
public class PublicAccountResourceController {
    private final AccountResourceService service;

    public PublicAccountResourceController(AccountResourceService service) {
        this.service = service;
    }

    @PostMapping("/messages")
    public ApiResponse<VerificationIngestResultDto> ingest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody InboundVerificationMessageRequest request) {
        return ApiResponse.ok(service.ingest(authorization, request));
    }

    @GetMapping("/codes/latest")
    public ApiResponse<LatestVerificationCodeDto> latest(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String phone, @RequestParam(required = false) Integer maxAgeSeconds) {
        return ApiResponse.ok(service.latestCode(authorization, phone, maxAgeSeconds));
    }
}
