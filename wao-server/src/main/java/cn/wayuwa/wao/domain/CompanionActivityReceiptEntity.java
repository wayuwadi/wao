package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 一台桌宠设备对一条已结束活动的已读确认。 */
@TableName("companion_activity_receipt")
public class CompanionActivityReceiptEntity {
    @TableId
    private String id;
    private String deviceId;
    private String activityId;
    private LocalDateTime readAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getActivityId() { return activityId; }
    public void setActivityId(String activityId) { this.activityId = activityId; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
