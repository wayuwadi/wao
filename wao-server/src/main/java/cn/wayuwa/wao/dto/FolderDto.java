package cn.wayuwa.wao.dto;

import java.util.List;

/** 知识库文件夹（含其下文档 id 顺序列表）。 */
public record FolderDto(String id, String name, List<String> docs) {
}
