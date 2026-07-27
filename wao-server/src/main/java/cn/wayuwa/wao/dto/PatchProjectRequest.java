package cn.wayuwa.wao.dto;

/** 局部更新项目请求。空字段不改。 */
public record PatchProjectRequest(
        String name,
        String area,
        String goal,
        String icon,
        /** 这个项目的动静要不要发飞书群；null 表示不改。 */
        Boolean notifyEnabled) {
}
