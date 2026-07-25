package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建知识库请求。project 非空时为项目知识库，不可再改名 / 改简介。 */
public record CreateKbRequest(
        @NotBlank String name,
        String desc,
        String icon,
        String color,
        String project,
        String group,
        String perm) {
}
