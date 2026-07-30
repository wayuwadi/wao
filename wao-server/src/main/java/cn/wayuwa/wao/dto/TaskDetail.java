package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** task.detail_json 的解析结构。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDetail(
        List<String> parts,
        List<Step> steps,
        List<Step> subs,
        List<FileRef> files,
        List<String> docs,
        List<TaskLink> linkTasks,
        List<CommentDto> comments,
        List<CustomProp> custom,
        List<Activity> activity,
        List<ImageRef> images,
        /** 所属任务分组 id（项目分组视图）；未归组时为空。 */
        String groupId,
        /** 日历时间段开始（本地 ISO 日期时间）。 */
        String startAt,
        /** 日历时间段结束（本地 ISO 日期时间）。 */
        String endAt) {

    /** 兼容尚未引入日历时间段的既有调用。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments,
            List<CustomProp> custom, List<Activity> activity, List<ImageRef> images, String groupId) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, groupId, null, null);
    }

    /** 兼容尚未引入任务分组的既有调用。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments,
            List<CustomProp> custom, List<Activity> activity, List<ImageRef> images) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, null, null, null);
    }
}
