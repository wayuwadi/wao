package cn.wayuwa.wao.service;

import cn.wayuwa.wao.common.BadRequestException;
import cn.wayuwa.wao.dto.TaskDto;
import java.time.*;
import java.util.List;
import java.util.Set;

final class TaskTimeCalendar {
    private TaskTimeCalendar() {}
    static TaskTimeRule normalize(TaskTimeRule r) {
        if (r == null || r.repeat == null || r.mode == null || r.anchor == null || !Set.of("none", "daily", "weekly", "monthly", "yearly", "weekdays").contains(r.repeat)
                || !Set.of("scheduled", "completion").contains(r.mode) || !Set.of("start", "due").contains(r.anchor))
            throw new BadRequestException("重复规则不合法");
        if (r.interval < 1 || r.interval > 365 || r.revision < 0) throw new BadRequestException("重复间隔需为 1–365");
        if (r.remindBefore == null || r.remindBefore.size() > 5 || r.remindBefore.stream().anyMatch(v -> v == null || v < 0 || v > 525600))
            throw new BadRequestException("最多 5 个提醒，提前时间需在一年内");
        r.remindBefore = r.remindBefore.stream().distinct().sorted().toList();
        try { if (r.reminderTime == null || !r.reminderTime.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException(); LocalTime.parse(r.reminderTime); }
        catch (RuntimeException e) { throw new BadRequestException("请选择有效的提醒时间"); }
        r.until = r.until == null ? "" : r.until;
        if (!r.until.isEmpty()) date(r.until);
        return r;
    }
    static LocalDate date(String value) {
        try { LocalDate d=LocalDate.parse(value); if(d.getYear()<1900 || d.getYear()>2100)throw new IllegalArgumentException();return d; }
        catch (RuntimeException e) { throw new BadRequestException("请选择 1900–2100 年内的有效日期"); }
    }
    static LocalDate anchor(TaskDto task, TaskTimeRule r) {
        String value = "due".equals(r.anchor) ? task.due() : task.startAt()!=null && !task.startAt().isBlank() ? task.startAt().substring(0,10) : task.start();
        return value == null || value.isBlank() ? null : date(value);
    }
    static LocalDateTime reminderAnchor(TaskDto task, TaskTimeRule r) {
        LocalDate d=anchor(task,r);if(d==null)return null;
        if ("start".equals(r.anchor) && task.startAt()!=null && !task.startAt().isBlank()) return LocalDateTime.parse(task.startAt());
        return d.atTime(LocalTime.parse(r.reminderTime));
    }
    static LocalDate next(TaskTimeRule r, LocalDate base, LocalDate today) {
        if ("none".equals(r.repeat)) return null;
        LocalDate result=step(r,base,r.mode.equals("completion")?base.getDayOfMonth():r.anchorDay);
        // Scheduled series catches up to the next future occurrence, without creating a backlog.
        if (r.mode.equals("scheduled")) {
            int guard=0;
            while(!result.isAfter(today) && guard++<80000) result=step(r,result,r.anchorDay);
        }
        if(result.getYear()>2100 || (!r.until.isBlank() && result.isAfter(date(r.until))))return null;
        return result;
    }
    private static LocalDate step(TaskTimeRule r,LocalDate base,int anchorDay) {
        int day=anchorDay>0?anchorDay:base.getDayOfMonth();
        return switch(r.repeat) {
            case "daily" -> base.plusDays(r.interval);
            case "weekly" -> base.plusWeeks(r.interval);
            case "monthly" -> {YearMonth month=YearMonth.from(base).plusMonths(r.interval);yield month.atDay(Math.min(day,month.lengthOfMonth()));}
            case "yearly" -> {YearMonth month=YearMonth.of(base.getYear()+r.interval,base.getMonth());yield month.atDay(Math.min(day,month.lengthOfMonth()));}
            case "weekdays" -> {LocalDate d=base;for(int i=0;i<r.interval;i++){do{d=d.plusDays(1);}while(d.getDayOfWeek().getValue()>5);}yield d;}
            default -> throw new BadRequestException("请先设置重复规则");
        };
    }
}
