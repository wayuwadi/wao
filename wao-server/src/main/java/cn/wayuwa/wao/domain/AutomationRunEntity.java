package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 自动化运行记录表实体。 */
@TableName("automation_run")
public class AutomationRunEntity {

    @TableId
    private String id;
    private String jobId;
    private String runTimeLabel;
    private String result;
    private Integer durationMs;
    private Integer ord;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }
    public String getRunTimeLabel() { return runTimeLabel; }
    public void setRunTimeLabel(String runTimeLabel) { this.runTimeLabel = runTimeLabel; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public Integer getDurationMs() { return durationMs; }
    public void setDurationMs(Integer durationMs) { this.durationMs = durationMs; }
    public Integer getOrd() { return ord; }
    public void setOrd(Integer ord) { this.ord = ord; }
}
