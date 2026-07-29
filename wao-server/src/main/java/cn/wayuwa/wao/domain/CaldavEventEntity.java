package cn.wayuwa.wao.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 从 CalDAV 同步下来的事件（重复事件已展开为多条）。只读副本，每次同步整体重建。 */
@TableName("caldav_event")
public class CaldavEventEntity {

    @TableId
    private String id;
    private String calendarId;
    private String uid;
    private String summary;
    private String location;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private Boolean allDay;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCalendarId() { return calendarId; }
    public void setCalendarId(String calendarId) { this.calendarId = calendarId; }
    public String getUid() { return uid; }
    public void setUid(String uid) { this.uid = uid; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public LocalDateTime getStartAt() { return startAt; }
    public void setStartAt(LocalDateTime startAt) { this.startAt = startAt; }
    public LocalDateTime getEndAt() { return endAt; }
    public void setEndAt(LocalDateTime endAt) { this.endAt = endAt; }
    public Boolean getAllDay() { return allDay; }
    public void setAllDay(Boolean allDay) { this.allDay = allDay; }
}
