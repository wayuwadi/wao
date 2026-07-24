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
        List<Activity> activity) {
}
