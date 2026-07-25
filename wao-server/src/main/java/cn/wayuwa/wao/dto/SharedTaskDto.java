package cn.wayuwa.wao.dto;

import java.util.List;

/**
 * 公开分享的任务视图 —— 只包含描述性内容，不带负责人、参与人、评论、活动等内部信息。
 *
 * @param kind    "task"（含子任务）或 "sub"（只有该子任务本身）
 * @param id      任务 id
 * @param subId   子任务标识，kind 为 "task" 时为 null
 * @param title   标题
 * @param status  状态
 * @param pr      优先级（子任务分享时为 null）
 * @param due     截止日期（子任务分享时为 null）
 * @param projectId 所属项目 id，用于登录后直接定位原任务
 * @param project 所属项目名
 * @param desc    描述（Markdown）
 * @param images  内嵌图片
 * @param subs    子任务列表，kind 为 "sub" 时为空
 */
public record SharedTaskDto(
        String kind,
        String id,
        String subId,
        String title,
        String status,
        String pr,
        String due,
        String projectId,
        String project,
        String desc,
        List<ImageRef> images,
        List<Step> subs) {
}
