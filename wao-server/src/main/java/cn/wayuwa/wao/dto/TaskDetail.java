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
        List<Activity> activity) {
}
