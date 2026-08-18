package cn.wayuwa.wao.dto;

import java.util.List;

/** 一次提交知识库完整的文档树顺序，兼顾同文件夹排序与跨文件夹移动。 */
public record ReorderDocsRequest(List<FolderOrder> folders) {
    public record FolderOrder(String folderId, List<String> docIds) {
    }
}
