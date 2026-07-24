package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新建项目请求。visAll=true 表示全部成员可见。 */
public record CreateProjectRequest(
        @NotBlank String name,
        String area,
        String goal,
        boolean visAll) {
}
