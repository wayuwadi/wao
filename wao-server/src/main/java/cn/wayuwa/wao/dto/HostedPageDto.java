package cn.wayuwa.wao.dto;

/** HTML 托管页列表项。 */
public record HostedPageDto(String id, String slug, String title, String fileName, String sourceType, boolean hasIcon,
        String updatedAt, String url) {
    /** 滚动发布兼容：旧调用创建的普通页面默认视为人工上传。 */
    public HostedPageDto(String id, String slug, String title, String fileName, boolean hasIcon,
            String updatedAt, String url) {
        this(id, slug, title, fileName, "MANUAL_UPLOAD", hasIcon, updatedAt, url);
    }
}
