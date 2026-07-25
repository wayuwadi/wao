package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/** 批量把任务加入 AI 执行队列。 */
public record CreateAiRunsRequest(@NotEmpty List<String> taskIds) {
}
