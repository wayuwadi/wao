package cn.wayuwa.wao.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 某成员某日的最小考勤事实；远端值与手工覆盖分栏，随时可以恢复远端数据。 */
@TableName("attendance_day")
public class AttendanceDayEntity {
    private String memberId; private LocalDate attendanceDate;
    private LocalDateTime remoteClockInAt; private LocalDateTime remoteClockOutAt;
    private String remoteDayType; private String remoteAnomaly;
    private LocalDateTime manualClockInAt; private LocalDateTime manualClockOutAt; private String manualDayType;
    private Boolean manualOverride; private LocalDateTime syncedAt; private LocalDateTime createdAt; private LocalDateTime updatedAt;
    public String getMemberId(){return memberId;} public void setMemberId(String v){memberId=v;}
    public LocalDate getAttendanceDate(){return attendanceDate;} public void setAttendanceDate(LocalDate v){attendanceDate=v;}
    public LocalDateTime getRemoteClockInAt(){return remoteClockInAt;} public void setRemoteClockInAt(LocalDateTime v){remoteClockInAt=v;}
    public LocalDateTime getRemoteClockOutAt(){return remoteClockOutAt;} public void setRemoteClockOutAt(LocalDateTime v){remoteClockOutAt=v;}
    public String getRemoteDayType(){return remoteDayType;} public void setRemoteDayType(String v){remoteDayType=v;}
    public String getRemoteAnomaly(){return remoteAnomaly;} public void setRemoteAnomaly(String v){remoteAnomaly=v;}
    public LocalDateTime getManualClockInAt(){return manualClockInAt;} public void setManualClockInAt(LocalDateTime v){manualClockInAt=v;}
    public LocalDateTime getManualClockOutAt(){return manualClockOutAt;} public void setManualClockOutAt(LocalDateTime v){manualClockOutAt=v;}
    public String getManualDayType(){return manualDayType;} public void setManualDayType(String v){manualDayType=v;}
    public Boolean getManualOverride(){return manualOverride;} public void setManualOverride(Boolean v){manualOverride=v;}
    public LocalDateTime getSyncedAt(){return syncedAt;} public void setSyncedAt(LocalDateTime v){syncedAt=v;}
    public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
    public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
