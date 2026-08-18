package cn.wayuwa.wao.dto;

/** 工作台中的一条真实页面链接。 */
public record WorkspaceLinkDto(
        String id,
        String title,
        String url,
        String description,
        String environment,
        String icon,
        int order,
        boolean favorite) {
}
