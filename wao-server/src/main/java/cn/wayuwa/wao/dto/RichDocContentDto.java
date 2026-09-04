package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 高级文档正文。JSON 是编辑与渲染的完整事实源，Markdown 是分享、搜索和 AI 使用的可读投影。
 *
 * @param version 结构版本，当前固定为 1
 * @param document Tiptap JSON；服务端生成的标准 Markdown 文档可暂时为空
 * @param markdown 不含二进制数据的 Markdown 投影
 */
public record RichDocContentDto(Integer version, JsonNode document, String markdown) {
}
