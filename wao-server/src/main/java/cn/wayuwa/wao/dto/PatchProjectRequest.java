package cn.wayuwa.wao.dto;

import java.util.List;

/** 局部更新项目请求。空字段不改。 */
public record PatchProjectRequest(
        String name,
        String area,
        String status,
        String goal,
        String icon,
        /** 这个项目的动静要不要发飞书群；null 表示不改。 */
        Boolean notifyEnabled,
        /** 自定义任务分组的完整列表（整表替换）；null 表示不改，空数组表示清空。 */
        List<TaskGroupDto> taskGroups,
        /** yyyy-MM-dd；省略或 null 表示不改，空串表示清空。 */
        String due,
        /** 负责人替换成员列表；null 不改，空数组保留负责人。 */
        List<String> members,
        /** 编辑前的成员快照；修改成员时必填，冲突返回 409。 */
        List<String> expectedMembers) {

    /** 兼容既有项目更新，不修改成员列表。 */
    public PatchProjectRequest(String name, String area, String status, String goal, String icon,
                               Boolean notifyEnabled, List<TaskGroupDto> taskGroups, String due) {
        this(name, area, status, goal, icon, notifyEnabled, taskGroups, due, null, null);
    }

    /** 兼容既有调用，不改变历史截止日期。 */
    public PatchProjectRequest(String name, String area, String status, String goal, String icon,
                               Boolean notifyEnabled, List<TaskGroupDto> taskGroups) {
        this(name, area, status, goal, icon, notifyEnabled, taskGroups, null);
    }

    /** 兼容尚未引入任务分组的既有调用。 */
    public PatchProjectRequest(String name, String area, String goal, String icon, Boolean notifyEnabled) {
        this(name, area, null, goal, icon, notifyEnabled, null);
    }

    /** 兼容尚未引入项目状态维护的任务分组调用。 */
    public PatchProjectRequest(String name, String area, String goal, String icon, Boolean notifyEnabled,
                               List<TaskGroupDto> taskGroups) {
        this(name, area, null, goal, icon, notifyEnabled, taskGroups);
    }
}
