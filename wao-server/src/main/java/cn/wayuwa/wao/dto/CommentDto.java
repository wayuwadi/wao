package cn.wayuwa.wao.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 任务评论。
 *
 * <p>{@code id}（{@code c} + 时间戳）是修改 / 删除时的定位依据。历史评论没有这个字段，
 * 读到 null 时按数组下标兜底 —— 与子任务 id 一个路数，不能用会漂移的下标当唯一标识。
 *
 * @param id   评论 id，老数据可能为 null
 * @param who  评论人 member id
 * @param time 评论时间（ISO 时间戳）
 * @param text 正文
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommentDto(String id, String who, String time, String text) {
}
