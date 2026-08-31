package cn.wayuwa.wao.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

/**
 * 批量把任务加入 AI 执行队列。
 *
 * @param subId    只跑某一条子任务时传它的标识；留空表示跑整个任务，此时 taskIds 可以是多条
 * @param engine   使用的 AI 工具（codex / claude）；留空表示沿用项目默认工具
 * @param runnerId 指定执行电脑；留空表示由任意在线且支持该工具的电脑领取
 * @param accountId 指定这次运行使用的集中账号；留空表示沿用 Runner 本地登录态
 * @param model Codex 本次使用的模型；留空时使用后台默认值
 * @param reasoningEffort Codex 本次使用的推理强度；留空时使用后台默认值
 * @param planMode Codex 是否先规划再连续执行；问题自动采用推荐项
 * @param goalMode Codex 是否使用 app-server 原生目标模式；与 Plan 模式互斥
 * @param mergeTasks 是否把同一项目的多条主任务合并为一次运行
 */
public record CreateAiRunsRequest(@NotEmpty List<String> taskIds, String subId, String engine,
        String runnerId, String accountId, String model, String reasoningEffort, Boolean planMode,
        Boolean goalMode, Boolean mergeTasks) {

    public CreateAiRunsRequest(List<String> taskIds, String subId, String engine, String runnerId) {
        this(taskIds, subId, engine, runnerId, null, null, null, false, false, false);
    }
}
