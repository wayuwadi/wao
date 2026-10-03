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
        String endAt,
        /** 按时间顺序保存的 PDCA 轮次；空列表表示这条任务未启用 PDCA。 */
        List<PdcaCycle> pdcaCycles,
        /** 关联的人工上传 HTML 托管页 id；历史任务缺省为空。 */
        List<String> hostedPages, String workItemType, String schedulePeriod) {
    /** 兼容未携带粗粒度排时的历史构造和数据。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments, List<CustomProp> custom,
            List<Activity> activity, List<ImageRef> images, String groupId, String startAt, String endAt,
            List<PdcaCycle> pdcaCycles, List<String> hostedPages, String workItemType) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images,
                groupId, startAt, endAt, pdcaCycles, hostedPages, workItemType, null);
    }

    /** 返回排时变更后保留其余详情的副本。 */
    public TaskDetail withSchedule(String newStartAt, String newEndAt, String period) {
        return new TaskDetail(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images,
                groupId, newStartAt, newEndAt, pdcaCycles, hostedPages, workItemType, period);
    }
    /** 兼容未携带工作项类型的历史调用。 */
    public TaskDetail(List<String> parts,
        List<Step> steps,
        List<Step> subs,
        List<FileRef> files,
        List<String> docs,
        List<TaskLink> linkTasks,
        List<CommentDto> comments,
        List<CustomProp> custom,
        List<Activity> activity,
        List<ImageRef> images,

        String groupId,

        String startAt,

        String endAt,

        List<PdcaCycle> pdcaCycles,

        List<String> hostedPages) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, groupId, startAt, endAt, pdcaCycles, hostedPages, null);
    }
    /** 返回指定工作项类型的副本。 */
    public TaskDetail withWorkItemType(String type) {
        return new TaskDetail(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, groupId, startAt, endAt, pdcaCycles, hostedPages, type, schedulePeriod);
    }


    /** 兼容尚未引入 HTML 关联的既有调用。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments,
            List<CustomProp> custom, List<Activity> activity, List<ImageRef> images, String groupId,
            String startAt, String endAt, List<PdcaCycle> pdcaCycles) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, groupId,
                startAt, endAt, pdcaCycles, null);
    }

    /** 兼容尚未引入 PDCA 的既有调用。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments,
            List<CustomProp> custom, List<Activity> activity, List<ImageRef> images, String groupId,
            String startAt, String endAt) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, groupId,
                startAt, endAt, null, null);
    }

    /** 兼容尚未引入日历时间段的既有调用。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments,
            List<CustomProp> custom, List<Activity> activity, List<ImageRef> images, String groupId) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, groupId, null, null, null, null);
    }

    /** 兼容尚未引入任务分组的既有调用。 */
    public TaskDetail(List<String> parts, List<Step> steps, List<Step> subs, List<FileRef> files,
            List<String> docs, List<TaskLink> linkTasks, List<CommentDto> comments,
            List<CustomProp> custom, List<Activity> activity, List<ImageRef> images) {
        this(parts, steps, subs, files, docs, linkTasks, comments, custom, activity, images, null, null, null, null, null);
    }
}
