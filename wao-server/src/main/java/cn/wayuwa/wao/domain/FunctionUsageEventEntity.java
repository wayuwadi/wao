package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 登录成员真实打开一个 WAO 功能的事件。 */
@TableName("function_usage_event")
public class FunctionUsageEventEntity {
    @TableId(type = IdType.AUTO) private Long id;
    private String memberId;
    private String featureId;
    private String source;
    private LocalDateTime openedAt;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public String getMemberId() { return memberId; }
    public void setMemberId(String value) { memberId = value; }
    public String getFeatureId() { return featureId; }
    public void setFeatureId(String value) { featureId = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public LocalDateTime getOpenedAt() { return openedAt; }
    public void setOpenedAt(LocalDateTime value) { openedAt = value; }
}
