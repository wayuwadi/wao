package cn.wayuwa.wao.service;

/** 自动化执行器的真实结果；可选关联本次创建的 AI Run。 */
public record JobExecutionResult(String message, String aiRunId) {

    /** 兼容不创建 AI Run 的现有执行器。 */
    public static JobExecutionResult messageOnly(String message) {
        return new JobExecutionResult(message, null);
    }
}
