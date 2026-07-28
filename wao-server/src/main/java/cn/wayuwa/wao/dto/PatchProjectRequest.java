package cn.wayuwa.wao.dto;

import java.util.List;

/** 局部更新项目请求。空字段不改。 */
public record PatchProjectRequest(
        String name,
        String area,
        String goal,
        String icon,
        /** 这个项目的动静要不要发飞书群；null 表示不改。 */
        Boolean notifyEnabled,
        /** 自定义任务分组的完整列表（整表替换）；null 表示不改，空数组表示清空。 */
        List<TaskGroupDto> taskGroups) {

    /** 兼容尚未引入任务分组的既有调用。 */
    public PatchProjectRequest(String name, String area, String goal, String icon, Boolean notifyEnabled) {
        this(name, area, goal, icon, notifyEnabled, null);
    }
}
