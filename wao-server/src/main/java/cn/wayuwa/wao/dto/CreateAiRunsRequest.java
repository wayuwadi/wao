package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * 批量把任务加入 AI 执行队列。
 *
 * @param subId    只跑某一条子任务时传它的标识；留空表示跑整个任务，此时 taskIds 可以是多条
 * @param engine   使用的 AI 工具（codex / claude）；留空表示沿用项目默认工具
 * @param runnerId 指定执行电脑；留空表示由任意在线且支持该工具的电脑领取
 */
public record CreateAiRunsRequest(@NotEmpty List<String> taskIds, String subId, String engine, String runnerId) {
}
