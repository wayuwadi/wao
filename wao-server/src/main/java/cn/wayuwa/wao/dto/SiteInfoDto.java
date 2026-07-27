package cn.wayuwa.wao.dto;

/** 网站公开信息，供设置页维护并在各页面页脚展示。 */
public record SiteInfoDto(String siteName, String description, String icpNumber, String icpUrl) { }
