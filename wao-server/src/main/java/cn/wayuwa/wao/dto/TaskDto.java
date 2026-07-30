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
        String createdBy,
        String completedAt,
        /** 所属任务分组 id（项目分组视图）；未归组时为空。 */
        String groupId,
        /** 日历时间段开始（本地 ISO 日期时间）。 */
        String startAt,
        /** 日历时间段结束（本地 ISO 日期时间）。 */
        String endAt) {

    /** 兼容尚未引入日历时间段、但已经带任务分组的既有调用。 */
    public TaskDto(String id, String title, String project, String status, String pr, String owner,
            List<String> parts, String start, String due, String waiting, String desc,
            List<Step> steps, List<Step> subs, List<FileRef> files, List<String> docs,
            List<TaskLink> linkTasks, List<CommentDto> comments, List<CustomProp> custom,
            List<Activity> activity, List<ImageRef> images, String createdBy, String completedAt,
            String groupId) {
        this(id, title, project, status, pr, owner, parts, start, due, waiting, desc, steps, subs, files,
                docs, linkTasks, comments, custom, activity, images, createdBy, completedAt, groupId, null, null);
    }

    /** 兼容尚未引入任务分组的既有调用。 */
    public TaskDto(String id, String title, String project, String status, String pr, String owner,
            List<String> parts, String start, String due, String waiting, String desc,
            List<Step> steps, List<Step> subs, List<FileRef> files, List<String> docs,
            List<TaskLink> linkTasks, List<CommentDto> comments, List<CustomProp> custom,
            List<Activity> activity, List<ImageRef> images, String createdBy, String completedAt) {
        this(id, title, project, status, pr, owner, parts, start, due, waiting, desc, steps, subs, files,
                docs, linkTasks, comments, custom, activity, images, createdBy, completedAt, null, null, null);
    }

    /** 兼容尚未写入完成时间的既有调用。 */
    public TaskDto(String id, String title, String project, String status, String pr, String owner,
            List<String> parts, String start, String due, String waiting, String desc,
            List<Step> steps, List<Step> subs, List<FileRef> files, List<String> docs,
            List<TaskLink> linkTasks, List<CommentDto> comments, List<CustomProp> custom,
            List<Activity> activity, List<ImageRef> images, String createdBy) {
        this(id, title, project, status, pr, owner, parts, start, due, waiting, desc, steps, subs, files,
                docs, linkTasks, comments, custom, activity, images, createdBy, null, null, null, null);
    }

    /** 兼容尚未写入创建人的既有调用与历史任务。 */
    public TaskDto(String id, String title, String project, String status, String pr, String owner,
            List<String> parts, String start, String due, String waiting, String desc,
            List<Step> steps, List<Step> subs, List<FileRef> files, List<String> docs,
            List<TaskLink> linkTasks, List<CommentDto> comments, List<CustomProp> custom,
            List<Activity> activity, List<ImageRef> images) {
        this(id, title, project, status, pr, owner, parts, start, due, waiting, desc, steps, subs, files,
                docs, linkTasks, comments, custom, activity, images, null, null, null, null, null);
    }
}
