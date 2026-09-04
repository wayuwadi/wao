package cn.wayuwa.wao.dto;

import java.util.List;

/** 局部更新文档请求。空字段不改；folderId 非空时表示把文档移动到该文件夹。 */
public record PatchDocRequest(
        String title,
        List<DocBlockDto> blocks,
        RichDocContentDto content,
        List<DocLinkDto> links,
        String folderId,
        Integer expectedRev) {
    public PatchDocRequest(String title, List<DocBlockDto> blocks, List<DocLinkDto> links, String folderId) {
        this(title, blocks, null, links, folderId, null);
    }
}
