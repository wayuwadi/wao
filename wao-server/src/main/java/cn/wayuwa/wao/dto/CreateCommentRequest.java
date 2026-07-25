package cn.wayuwa.wao.dto;

import jakarta.validation.constraints.NotBlank;

/** 新增任务评论请求。 */
public record CreateCommentRequest(@NotBlank String text) {
}
