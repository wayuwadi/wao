package cn.wayuwa.wao.service;

import java.time.LocalDateTime;

import cn.wayuwa.wao.domain.AutomationJobEntity;

/**
 * 自动化任务的真实执行器。
 *
 * <p>{@code automation_job.handler} 里登记名字，{@link AutomationService} 触发时按名字找到实现并执行；
 * 没有登记执行器的任务失败关闭，步骤描述不能替代真实执行。
 */
public interface JobHandler {

    /** 执行器名，与 {@code automation_job.handler} 对应。 */
    String name();

    /**
     * 执行一次。
     *
     * @return 写进运行记录的结果文案，如「成功 · 2026-07-25 共 42 条消息」
     * @throws RuntimeException 执行失败时，异常消息会被记进运行记录
     */
    String execute();

    /**
     * 执行某一条任务。无须任务上下文的旧执行器继续复用 {@link #execute()}；需要读取通知正文等
     * 任务配置的执行器可覆盖本方法。
     */
    default String execute(AutomationJobEntity job) {
        return execute();
    }

    /**
     * 执行某一次计划触发。定时任务可用计划时间确定业务日期，避免延迟或补跑时错取执行当天。
     *
     * @param job             自动化任务
     * @param scheduledFireAt 本次 Cron 计划触发时间；手动触发时为 null
     * @return 写进运行记录的结果文案
     */
    default String execute(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        return execute(job);
    }

    /**
     * 执行并返回可持久化的结构化关联。旧执行器继续只提供结果文案；创建 AI Run 的执行器覆盖此入口。
     *
     * @param job             自动化任务
     * @param scheduledFireAt 本次 Cron 计划触发时间；手动触发时为 null
     * @return 结果文案及可选 AI Run id
     */
    default JobExecutionResult executeWithMetadata(AutomationJobEntity job, LocalDateTime scheduledFireAt) {
        return JobExecutionResult.messageOnly(execute(job, scheduledFireAt));
    }
}
