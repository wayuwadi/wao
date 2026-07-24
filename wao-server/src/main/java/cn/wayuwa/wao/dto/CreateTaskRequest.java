package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/** 新建任务请求。due 为 ISO 日期（YYYY-MM-DD）。 */
public record CreateTaskRequest(
        @NotBlank String title,
        @NotBlank String project,
        String pr,
        String owner,
        String due,
        String desc,
        List<ImageRef> images) {
}
