package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 自动化运行记录表实体。 */
@TableName("automation_run")
public class AutomationRunEntity {

    @TableId
    private String id;
    private String jobId;
    private String runTimeLabel;
    private LocalDateTime runAt;
    private String result;
    /** 本次自动化创建的真实 AI Run；跳过或执行失败时为空。 */
    private String aiRunId;
    private String executorId;
    private Integer durationMs;
    private Integer ord;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getRunTimeLabel() { return runTimeLabel; }
    public void setRunTimeLabel(String runTimeLabel) { this.runTimeLabel = runTimeLabel; }
    public LocalDateTime getRunAt() { return runAt; }
    public void setRunAt(LocalDateTime runAt) { this.runAt = runAt; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getAiRunId() { return aiRunId; }
    public void setAiRunId(String aiRunId) { this.aiRunId = aiRunId; }
    public String getExecutorId() { return executorId; }
    public void setExecutorId(String executorId) { this.executorId = executorId; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
