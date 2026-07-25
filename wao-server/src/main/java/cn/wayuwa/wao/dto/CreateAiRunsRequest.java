package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * 批量把任务加入 AI 执行队列。
 *
 * @param engine   使用的 AI 工具（codex / claude）；留空表示沿用项目默认工具
 * @param runnerId 指定执行电脑；留空表示由任意在线且支持该工具的电脑领取
 */
public record CreateAiRunsRequest(@NotEmpty List<String> taskIds, String engine, String runnerId) {
}
