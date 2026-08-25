package cn.wayuwa.wao.domain;

/** 一次 AI Run 关联的一条任务；ord 保留用户勾选时的上下文顺序。 */
public class AiTaskRunTaskEntity {
    private String runId;
    private String taskId;
    private Integer ord;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
