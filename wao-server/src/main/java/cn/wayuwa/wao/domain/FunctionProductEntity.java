package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 功能产品的全局满意分与已经确认的代码迭代次数。 */
@TableName("function_product")
public class FunctionProductEntity {
    @TableId private String featureId;
    private Integer manualAdjustment;
    private Integer iterationCount;
    private LocalDateTime updatedAt;
    public String getFeatureId() { return featureId; }
    public void setFeatureId(String value) { featureId = value; }
    public Integer getManualAdjustment() { return manualAdjustment; }
    public void setManualAdjustment(Integer value) { manualAdjustment = value; }
    public Integer getIterationCount() { return iterationCount; }
    public void setIterationCount(Integer value) { iterationCount = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
