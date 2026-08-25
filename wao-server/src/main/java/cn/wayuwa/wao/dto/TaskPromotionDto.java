package cn.wayuwa.wao.dto;

/** 任务升级为项目后的原子快照，供前端同时替换项目和任务数据。 */
public record TaskPromotionDto(ProjectDto project, TaskDto task) {
}
