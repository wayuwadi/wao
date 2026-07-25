package cn.wayuwa.wao.dto;

/** 反向链接（按其他文档的出链动态计算得到，不落库）。 */
public record BacklinkDto(String type, String id, String snippet) {
}
