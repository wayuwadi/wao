package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** project.detail_json 的解析结构。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectDetail(
        List<String> members,
        List<Milestone> milestones,
        List<String> msDates,
        List<Integer> trend,
        List<Blocker> blockers,
        List<String> docs,
        List<FileRef> files,
        List<Activity> activity,
        String icon,
        /** 项目自定义的任务分组（分组视图），最多 6 个；老项目为空。 */
        List<TaskGroupDto> taskGroups) {

    /** 兼容尚未引入任务分组的既有调用。 */
    public ProjectDetail(List<String> members, List<Milestone> milestones, List<String> msDates,
            List<Integer> trend, List<Blocker> blockers, List<String> docs, List<FileRef> files,
            List<Activity> activity, String icon) {
        this(members, milestones, msDates, trend, blockers, docs, files, activity, icon, null);
    }
}
