package cn.wayuwa.wao.dto;

/** 局部更新任务请求，字段为空表示不修改。 */
public record PatchTaskRequest(
        String title,
        String status,
        String pr,
        String owner,
        String desc) {
}
