package cn.wayuwa.wao.dto;

/** 局部更新项目请求。空字段不改。 */
public record PatchProjectRequest(
        String name,
        String area,
        String goal,
        String icon) {
}
