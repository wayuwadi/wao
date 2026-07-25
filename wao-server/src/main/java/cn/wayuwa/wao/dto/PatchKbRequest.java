package cn.wayuwa.wao.dto;

/** 局部更新知识库请求。空字段不改。 */
public record PatchKbRequest(String name, String desc, String icon, String color, String group, String perm) {
}
