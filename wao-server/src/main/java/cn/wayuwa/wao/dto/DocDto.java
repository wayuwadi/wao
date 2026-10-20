package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 文档对外表示，字段与前端 {@code Doc} 一致。
 *
 * <p>{@code created / updated} 优先是 ISO-8601 时间戳，由前端转成「几分钟前」这类相对
 * 描述；只有升级前写入、没有真实时间戳的老文档才会是「7月12日」这种现成文案。
 */
public record DocDto(
        String id,
        String title,
        String author,
        String created,
        String updated,
        Boolean auto,
        List<DocLinkDto> links,
        List<DocBlockDto> blocks,
        RichDocContentDto content,
        List<BacklinkDto> backlinks,
        String kbId,
        String folderId,
        Integer rev,
        List<String> tags,
        boolean bodyLoaded,
        boolean systemGenerated,
        String pageType,
        String drawingId) {
    public DocDto(String id, String title, String author, String created, String updated, Boolean auto,
            List<DocLinkDto> links, List<DocBlockDto> blocks, RichDocContentDto content,
            List<BacklinkDto> backlinks, String kbId, String folderId, Integer rev, List<String> tags, boolean bodyLoaded, boolean systemGenerated) {
        this(id, title, author, created, updated, auto, links, blocks, content, backlinks, kbId, folderId, rev, tags, bodyLoaded, systemGenerated, "document", null);
    }
    /** 历史调用默认是个人记录，不能按作者、标签或 auto 推断系统来源。 */
    public DocDto(String id, String title, String author, String created, String updated, Boolean auto,
            List<DocLinkDto> links, List<DocBlockDto> blocks, RichDocContentDto content,
            List<BacklinkDto> backlinks, String kbId, String folderId, Integer rev, List<String> tags, boolean bodyLoaded) {
        this(id, title, author, created, updated, auto, links, blocks, content, backlinks, kbId, folderId, rev, tags, bodyLoaded, false);
    }
    public DocDto(String id, String title, String author, String created, String updated, Boolean auto,
            List<DocLinkDto> links, List<DocBlockDto> blocks, RichDocContentDto content,
            List<BacklinkDto> backlinks, String kbId, String folderId, Integer rev, List<String> tags) {
        this(id, title, author, created, updated, auto, links, blocks, content, backlinks, kbId, folderId, rev, tags, true);
    }
    public DocDto(String id, String title, String author, String created, String updated, Boolean auto,
            List<DocLinkDto> links, List<DocBlockDto> blocks, RichDocContentDto content,
            List<BacklinkDto> backlinks, String kbId, String folderId, Integer rev) {
        this(id, title, author, created, updated, auto, links, blocks, content, backlinks, kbId, folderId, rev, List.of());
    }
    /** 旧调用方兼容构造；高级正文为空时继续使用 blocks。 */
    public DocDto(String id, String title, String author, String created, String updated, Boolean auto,
            List<DocLinkDto> links, List<DocBlockDto> blocks, List<BacklinkDto> backlinks,
            String kbId, String folderId, Integer rev) {
        this(id, title, author, created, updated, auto, links, blocks, null, backlinks, kbId, folderId, rev);
    }
}
