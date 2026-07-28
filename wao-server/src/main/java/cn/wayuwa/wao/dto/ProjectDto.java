package cn.wayuwa.wao.dto;

import java.util.List;

/** 项目对外表示，字段与前端 {@code Project} 一致。 */
public record ProjectDto(
        String id,
        String name,
        String area,
        String status,
        String health,
        int progress,
        String owner,
        List<String> members,
        String start,
        String due,
        String visibility,
        String updated,
        String goal,
        List<Milestone> milestones,
        List<String> msDates,
        List<Integer> trend,
        List<Blocker> blockers,
        List<String> docs,
        List<FileRef> files,
        List<Activity> activity,
        String icon,
        boolean notifyEnabled,
        /** 自定义任务分组（分组视图），最多 6 个；没建过分组时为空数组。 */
        List<TaskGroupDto> taskGroups) {
}
