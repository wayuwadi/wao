package cn.wayuwa.wao.dto;

/** HTML 托管页列表项。 */
public record HostedPageDto(String id, String slug, String title, String fileName, boolean hasIcon,
        String updatedAt, String url) { }
