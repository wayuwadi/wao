package cn.wayuwa.wao.dto;

import java.util.List;

/** 任务对外表示，字段与前端 {@code Task} 一致。 */
public record TaskDto(
        String id,
        String title,
        String project,
        String status,
        String pr,
        String owner,
        List<String> parts,
        String start,
        String due,
        String waiting,
        String desc,
        List<Step> steps,
        List<Step> subs,
        List<FileRef> files,
        List<String> docs,
        List<TaskLink> linkTasks,
        List<CommentDto> comments,
        List<CustomProp> custom,
        List<Activity> activity,
        List<ImageRef> images,
        String createdBy) {

    /** 兼容尚未写入创建人的既有调用与历史任务。 */
    public TaskDto(String id, String title, String project, String status, String pr, String owner,
            List<String> parts, String start, String due, String waiting, String desc,
            List<Step> steps, List<Step> subs, List<FileRef> files, List<String> docs,
            List<TaskLink> linkTasks, List<CommentDto> comments, List<CustomProp> custom,
            List<Activity> activity, List<ImageRef> images) {
        this(id, title, project, status, pr, owner, parts, start, due, waiting, desc, steps, subs, files,
                docs, linkTasks, comments, custom, activity, images, null);
    }
}
