package cn.wayuwa.wao.service;

/**
 * 自动化任务的真实执行器。
 *
 * <p>{@code automation_job.handler} 里登记名字，{@link AutomationService} 触发时按名字找到实现并执行；
 * 没有登记执行器的任务仍是模拟运行（步骤只是描述性文本）。
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
}
