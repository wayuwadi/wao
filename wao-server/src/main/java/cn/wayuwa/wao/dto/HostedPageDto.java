package cn.wayuwa.wao.dto;

/** HTML 托管页列表项。 */
public record HostedPageDto(String id, String slug, String title, String fileName, String sourceType, boolean hasIcon,
        String updatedAt, String url, String projectId) {
    /** 滚动发布兼容：旧调用创建的普通页面默认视为人工上传。 */
    public HostedPageDto(String id, String slug, String title, String fileName, boolean hasIcon,
            String updatedAt, String url) {
        this(id, slug, title, fileName, "MANUAL_UPLOAD", hasIcon, updatedAt, url, null);
    }

    /** 滚动发布兼容：旧服务生成的来源类型尚未携带项目归属。 */
    public HostedPageDto(String id, String slug, String title, String fileName, String sourceType, boolean hasIcon,
            String updatedAt, String url) {
        this(id, slug, title, fileName, sourceType, hasIcon, updatedAt, url, null);
    }
}
