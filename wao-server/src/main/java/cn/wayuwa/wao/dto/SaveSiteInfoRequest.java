package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 保存网站名称、简介和 ICP 备案信息。 */
public record SaveSiteInfoRequest(
        @NotBlank @Size(max = 64) String siteName,
        @Size(max = 160) String description,
        @NotBlank @Size(max = 64) String icpNumber,
        @NotBlank @Size(max = 255) String icpUrl) { }
